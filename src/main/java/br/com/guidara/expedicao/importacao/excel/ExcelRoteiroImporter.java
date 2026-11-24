package br.com.guidara.expedicao.importacao.excel;

import br.com.guidara.expedicao.config.ImportacaoProperties;
import br.com.guidara.expedicao.domain.Carga;
import br.com.guidara.expedicao.domain.Importacao.OrigemImportacao;
import br.com.guidara.expedicao.domain.Pedido;
import br.com.guidara.expedicao.importacao.CargaBuilder;
import br.com.guidara.expedicao.importacao.ImportadorRoteiro;
import br.com.guidara.expedicao.importacao.TextoUtils;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static br.com.guidara.expedicao.importacao.TextoUtils.normalizar;

/**
 * Lê o "Relatório Roteiro Entrega" exportado para Excel. Aceita dois layouts:
 *
 * 1) Layout do relatório (blocos): para cada OC há um cabeçalho com "Nº OC", "Veículo: PUV-0E30",
 *    "Motorista", "Data Embarque", "Rota", seguido da linha de títulos (Fil | Pedido | Nota | ...)
 *    e das linhas de pedidos até "Total de Entregas".
 *
 * 2) Tabela plana: uma única linha de títulos com colunas "OC" e/ou "Placa" em cada linha de pedido.
 *
 * As colunas são localizadas pelo TEXTO do título (não pela posição), então colunas extras,
 * células mescladas e ordem diferente não quebram a leitura.
 */
@Component
public class ExcelRoteiroImporter implements ImportadorRoteiro {

    enum Coluna { FILIAL, PEDIDO, NOTA, CLIENTE, RAZAO_SOCIAL, CEP, ENDERECO, NUMERO, BAIRRO, CIDADE, PESO, PESO_BRUTO, VOLUMES, QTDE_FATURADA,
        OBSERVACOES, VALOR, PLACA, OC, STATUS, MOTORISTA, DATA_EMBARQUE }

    private static final Pattern PESO_TOTAL_BRUTO = Pattern.compile("PESO TOTAL BRUTO\\s*:?\\s*([\\d.,]+)");
    private static final Pattern PESO_TOTAL_LIQUIDO = Pattern.compile("PESO TOTAL LIQUIDO\\s*:?\\s*([\\d.,]+)");

    private final DetectorCorAmarela detector;
    private final double fracaoLinhaAmarela;

    public ExcelRoteiroImporter(DetectorCorAmarela detector, ImportacaoProperties props) {
        this.detector = detector;
        this.fracaoLinhaAmarela = props.importacao().fracaoLinhaAmarela();
    }

    @Override
    public boolean suporta(String nomeArquivo, String contentType) {
        String n = nomeArquivo == null ? "" : nomeArquivo.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".xlsm");
    }

    @Override
    public OrigemImportacao origem() {
        return OrigemImportacao.EXCEL;
    }

    @Override
    public ResultadoLeitura ler(byte[] conteudo, String nomeArquivo, String contentType) {
        // WorkbookFactory detecta sozinho .xls (HSSF) ou .xlsx (XSSF)
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(conteudo))) {
            List<String> avisos = new ArrayList<>();
            List<Carga> cargas = new ArrayList<>();
            SomadorColunas somador = new SomadorColunas();
            for (Sheet sheet : wb) {
                cargas.addAll(new LeitorAba(wb, sheet, avisos, somador).ler());
            }
            if (cargas.isEmpty()) {
                avisos.add("Nenhum pedido encontrado. Verifique se a planilha tem a linha de títulos (Pedido, Cliente...).");
            }
            avisos.addAll(somador.conferencia());
            return new ResultadoLeitura(cargas, avisos, somador.somas());
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o arquivo Excel: " + e.getMessage(), e);
        }
    }

    /** Estado de leitura de uma aba. */
    private class LeitorAba {
        private final Sheet sheet;
        private final List<String> avisos;
        private final DataFormatter fmt = new DataFormatter(new Locale("pt", "BR"));
        private final FormulaEvaluator evaluator;
        private final DetectorCorAmarela.Sessao cores = detector.novaSessao();

        /** Cargas na ordem de aparição. Chave = OC (layout de blocos) ou OC/placa (tabela plana). */
        private final Map<String, CargaBuilder> cargas = new LinkedHashMap<>();
        private CargaBuilder atual;
        private Map<Coluna, Integer> colunas;
        private Integer colunaAguardandoOc; // "Nº OC:" com o número na linha de baixo
        private final SomadorColunas somador;

        LeitorAba(Workbook wb, Sheet sheet, List<String> avisos, SomadorColunas somador) {
            this.sheet = sheet;
            this.avisos = avisos;
            this.somador = somador;
            this.evaluator = wb.getCreationHelper().createFormulaEvaluator();
            // Fórmulas que apontam para outra pasta de trabalho (ex.: PROCV em "[1]Planilha1" no
            // computador de quem gerou a planilha) usam o valor que o Excel deixou salvo na célula.
            this.evaluator.setIgnoreMissingWorkbooks(true);
        }

        List<Carga> ler() {
            for (Row row : sheet) {
                List<String> textos = textosDaLinha(row);
                if (textos.stream().allMatch(String::isBlank)) continue;

                if (lerCabecalhoDaCarga(row, textos)) continue;

                Map<Coluna, Integer> titulos = tentarMapearTitulos(textos);
                if (titulos != null) {
                    colunas = titulos;
                    somador.definirTitulos(textos);
                    continue;
                }

                String linhaNorm = normalizar(String.join(" ", textos));
                // Rodapé da OC: "Total de Entregas --> 7   Peso Total Líquido: 364,407   Peso Total Bruto: 357,196"
                Matcher bruto = PESO_TOTAL_BRUTO.matcher(linhaNorm);
                if (bruto.find() && atual != null) {
                    atual.pesoBrutoTotal = TextoUtils.numero(bruto.group(1));
                }
                Matcher liquido = PESO_TOTAL_LIQUIDO.matcher(linhaNorm);
                if (liquido.find() && atual != null) {
                    atual.pesoLiquidoTotal = TextoUtils.numero(liquido.group(1));
                }
                if (linhaNorm.startsWith("TOTAL DE ENTREGAS") || linhaNorm.startsWith("RESUMO POR CIDADE")
                        || linhaNorm.startsWith("PESO TOTAL")) {
                    colunas = null; // fim do bloco de pedidos
                    continue;
                }

                if (colunas != null) lerPedido(row, textos);
            }
            return cargas.values().stream().filter(c -> !c.vazio()).map(CargaBuilder::build).toList();
        }

        // ---------- cabeçalho da OC (layout de blocos) ----------

        private boolean lerCabecalhoDaCarga(Row row, List<String> textos) {
            boolean alterou = false;

            if (colunaAguardandoOc != null && colunas == null) {
                String oc = primeiroNumeroAte(textos, colunaAguardandoOc + 1);
                if (oc != null) {
                    atual.numeroOc = oc;
                    colunaAguardandoOc = null;
                    alterou = true;
                }
            }

            for (int i = 0; i < textos.size(); i++) {
                String t = normalizar(textos.get(i));
                if (t.isEmpty()) continue;

                boolean rotuloOc = t.startsWith("NO OC") || t.startsWith("N OC") || t.equals("OC:");
                // Dentro de um bloco de pedidos só "Nº OC" abre nova carga; os demais rótulos
                // são ignorados para não confundir cliente/endereço (ex.: "ROTA DO SOL LTDA") com cabeçalho.
                if (colunas != null && !rotuloOc) continue;

                if (rotuloOc) {
                    colunas = null;
                    novaCarga();
                    String resto = t.replaceFirst("^(NO|N)?\\s*OC[:\\s]*", "");
                    if (resto.matches("\\d+")) atual.numeroOc = resto;
                    else {
                        String vizinho = valorAposCelula(textos, i);
                        if (vizinho != null && vizinho.matches("\\d+")) atual.numeroOc = vizinho;
                        else colunaAguardandoOc = i;
                    }
                    alterou = true;
                } else if (t.matches("^VEICULO\\s*:.*")) {
                    garantirCarga();
                    String alvo = t.length() > "VEICULO:".length() ? t : Objects.toString(valorAposCelula(textos, i), "");
                    TextoUtils.extrairPlaca(alvo).ifPresent(p -> atual.placa = p);
                    alterou = true;
                } else if (t.matches("^MOTORISTA\\s*:.*")) {
                    garantirCarga();
                    String v = TextoUtils.depoisDoRotulo(t, "MOTORISTA");
                    if (v.isEmpty()) v = Objects.toString(valorAposCelulaSeNaoRotulo(textos, i), "");
                    atual.motorista = v.isEmpty() ? null : v;
                    alterou = true;
                } else if (t.matches("^DATA (DE )?EMBARQUE\\s*:.*")) {
                    garantirCarga();
                    String alvo = t + " " + Objects.toString(valorAposCelula(textos, i), "");
                    TextoUtils.extrairData(alvo).ifPresent(d -> atual.dataEmbarque = d);
                    alterou = true;
                } else if (t.matches("^ROTA\\s*:.*")) {
                    garantirCarga();
                    String v = TextoUtils.depoisDoRotulo(t, "ROTA");
                    if (v.isEmpty()) v = Objects.toString(valorAposCelulaSeNaoRotulo(textos, i), "");
                    atual.rota = v.isEmpty() ? null : v;
                    alterou = true;
                }
            }
            return alterou;
        }

        private void novaCarga() {
            atual = new CargaBuilder();
            cargas.put("BLOCO#" + cargas.size(), atual);
        }

        private void garantirCarga() {
            if (atual == null) novaCarga();
        }

        // ---------- títulos ----------

        private Map<Coluna, Integer> tentarMapearTitulos(List<String> textos) {
            Map<Coluna, Integer> m = new EnumMap<>(Coluna.class);
            for (int i = 0; i < textos.size(); i++) {
                Coluna c = identificarColuna(normalizar(textos.get(i)));
                if (c != null) m.putIfAbsent(c, i);
            }
            // Linha de títulos precisa ter ao menos Pedido + (Cliente ou Nota)
            boolean ehTitulo = m.containsKey(Coluna.PEDIDO)
                    && (m.containsKey(Coluna.CLIENTE) || m.containsKey(Coluna.NOTA));
            return ehTitulo ? m : null;
        }

        private Coluna identificarColuna(String t) {
            if (t.isEmpty()) return null;
            t = t.replace(":", "").trim();
            return switch (t) {
                case "FIL", "FILIAL" -> Coluna.FILIAL;
                case "PEDIDO", "NO PEDIDO", "NUM PEDIDO" -> Coluna.PEDIDO;
                case "NOTA", "NF", "NOTA FISCAL", "NFE", "NF-E" -> Coluna.NOTA;
                case "CLIENTE" -> Coluna.CLIENTE;
                case "RAZAO SOCIAL" -> Coluna.RAZAO_SOCIAL;
                case "CEP" -> Coluna.CEP;
                case "ENDERECO" -> Coluna.ENDERECO;
                case "NR", "NRO", "NUMERO", "NO" -> Coluna.NUMERO;
                case "BAIRRO" -> Coluna.BAIRRO;
                case "CIDADE" -> Coluna.CIDADE;
                case "VOLUMES", "VOLUME", "VOL", "QTDE" -> Coluna.VOLUMES;
                case "QTDE FATURADA", "QTD FATURADA", "QUANTIDADE FATURADA" -> Coluna.QTDE_FATURADA;
                case "PLACA", "VEICULO", "PLACA VEICULO" -> Coluna.PLACA;
                case "OC", "NO OC", "ORDEM DE CARGA" -> Coluna.OC;
                case "STATUS", "SITUACAO" -> Coluna.STATUS;
                case "MOTORISTA" -> Coluna.MOTORISTA;
                case "DATA EMBARQUE", "EMBARQUE" -> Coluna.DATA_EMBARQUE;
                default -> {
                    if (t.startsWith("PESO BRUTO")) yield Coluna.PESO_BRUTO;
                    if (t.startsWith("PESO")) yield Coluna.PESO;
                    if (t.startsWith("OBSERV")) yield Coluna.OBSERVACOES;
                    if (t.startsWith("VALOR") || t.equals("VLR") || t.startsWith("VL ")) yield Coluna.VALOR;
                    yield null;
                }
            };
        }

        // ---------- linha de pedido ----------

        private void lerPedido(Row row, List<String> textos) {
            String numeroPedido = get(textos, Coluna.PEDIDO);
            if (!numeroPedido.matches("\\d+")) return; // linha que não é pedido (subtotal, lixo...)
            if (ehLinhaDeTotal(textos)) {                // ex.: última linha do ERP com contagem e somas
                somador.registrarLinhaDeTotal(row);      // guardada só para conferência
                return;
            }
            somador.somarLinha(row);

            CargaBuilder destino = cargaDaLinha(textos);
            boolean amarelo = linhaAmarela(row) || statusIndicaNaoEnviado(textos);
            if (colunas.containsKey(Coluna.PESO_BRUTO)) {
                destino.somarPesoBruto(numero(row, Coluna.PESO_BRUTO, textos)); // tabela plana com peso bruto por linha
            }

            destino.pedidos.add(new Pedido(
                    vazioParaNull(get(textos, Coluna.FILIAL)),
                    numeroPedido,
                    vazioParaNull(get(textos, Coluna.NOTA)),
                    cliente(textos),
                    vazioParaNull(get(textos, Coluna.CEP)),
                    vazioParaNull(get(textos, Coluna.ENDERECO)),
                    vazioParaNull(get(textos, Coluna.NUMERO)),
                    vazioParaNull(get(textos, Coluna.BAIRRO)),
                    vazioParaNull(get(textos, Coluna.CIDADE)),
                    numero(row, Coluna.PESO, textos),
                    numero(row, Coluna.VOLUMES, textos),
                    vazioParaNull(get(textos, Coluna.OBSERVACOES)),
                    numero(row, Coluna.VALOR, textos),
                    numero(row, Coluna.QTDE_FATURADA, textos),
                    amarelo));
        }

        /**
         * Linha de totais (ex.: rodapé azul da exportação do ERP, com a contagem de pedidos na coluna
         * "Pedido" e as somas de Valor/Peso). Reconhecida quando:
         *  - alguma célula contém "TOTAL"; ou
         *  - todas as colunas descritivas do pedido (cliente, razão social, endereço, bairro, cidade, CEP)
         *    estão vazias — um pedido de verdade sempre tem cliente/endereço.
         */
        private boolean ehLinhaDeTotal(List<String> textos) {
            if (textos.stream().anyMatch(t -> normalizar(t).startsWith("TOTAL"))) return true;
            List<Coluna> descritivas = List.of(Coluna.CLIENTE, Coluna.RAZAO_SOCIAL, Coluna.ENDERECO,
                    Coluna.BAIRRO, Coluna.CIDADE, Coluna.CEP);
            boolean algumaMapeada = descritivas.stream().anyMatch(colunas::containsKey);
            return algumaMapeada && descritivas.stream()
                    .filter(colunas::containsKey)
                    .allMatch(c -> get(textos, c).isBlank());
        }

        /** "código - razão social" quando a planilha separa as duas colunas (como no relatório). */
        private String cliente(List<String> textos) {
            String codigo = get(textos, Coluna.CLIENTE).trim();
            String razao = get(textos, Coluna.RAZAO_SOCIAL).trim();
            if (!codigo.isEmpty() && !razao.isEmpty()) return codigo + " - " + razao;
            return vazioParaNull(codigo.isEmpty() ? razao : codigo);
        }

        /** Tabela plana: agrupa por OC/placa da própria linha. Layout de blocos: usa a carga corrente. */
        private CargaBuilder cargaDaLinha(List<String> textos) {
            boolean plana = colunas.containsKey(Coluna.PLACA) || colunas.containsKey(Coluna.OC);
            if (!plana) {
                garantirCarga();
                return atual;
            }
            String oc = get(textos, Coluna.OC);
            String placa = TextoUtils.extrairPlaca(get(textos, Coluna.PLACA)).orElse(null);
            String chave = "PLANA#" + oc + "#" + placa;
            return cargas.computeIfAbsent(chave, k -> {
                CargaBuilder b = new CargaBuilder();
                b.numeroOc = oc.isEmpty() ? null : oc;
                b.placa = placa;
                b.motorista = vazioParaNull(get(textos, Coluna.MOTORISTA));
                b.dataEmbarque = TextoUtils.extrairData(get(textos, Coluna.DATA_EMBARQUE)).orElse(null);
                return b;
            });
        }

        /**
         * A linha é "amarela" quando a célula do Pedido está amarela OU quando pelo menos
         * {@code fracaoLinhaAmarela} das células mapeadas preenchidas estão amarelas.
         * Isso tolera grifos parciais (ex.: só algumas colunas pintadas).
         */
        private boolean linhaAmarela(Row row) {
            Cell celPedido = row.getCell(colunas.get(Coluna.PEDIDO));
            if (cores.ehAmarela(celPedido)) return true;

            int preenchidas = 0, amarelas = 0;
            for (int idx : colunas.values()) {
                Cell c = row.getCell(idx);
                if (c == null || c.getCellType() == CellType.BLANK) continue;
                preenchidas++;
                if (cores.ehAmarela(c)) amarelas++;
            }
            return preenchidas > 0 && (double) amarelas / preenchidas >= fracaoLinhaAmarela;
        }

        /** Alternativa à cor: coluna Status/Observações com "NÃO ENVIADO". */
        private boolean statusIndicaNaoEnviado(List<String> textos) {
            String s = normalizar(get(textos, Coluna.STATUS) + " " + get(textos, Coluna.OBSERVACOES));
            return s.contains("NAO ENVIADO") || s.contains("NAO EMBARCADO") || s.contains("RETIRADO");
        }

        // ---------- utilitários ----------

        private List<String> textosDaLinha(Row row) {
            int ultima = Math.max(row.getLastCellNum(), 0);
            List<String> textos = new ArrayList<>(ultima);
            for (int i = 0; i < ultima; i++) {
                Cell c = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                textos.add(c == null ? "" : texto(c));
            }
            return textos;
        }

        private String texto(Cell c) {
            if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                return c.getLocalDateTimeCellValue().toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            }
            if (c.getCellType() != CellType.FORMULA) return fmt.formatCellValue(c).trim();
            try {
                return fmt.formatCellValue(c, evaluator).trim();
            } catch (RuntimeException e) {
                // Função não suportada pelo POI, referência quebrada etc.: não derruba a importação.
                return valorEmCache(c).trim();
            }
        }

        /** Último resultado calculado pelo Excel e gravado no arquivo junto com a fórmula. */
        private String valorEmCache(Cell c) {
            try {
                return switch (c.getCachedFormulaResultType()) {
                    case NUMERIC -> fmt.formatRawCellContents(c.getNumericCellValue(),
                            c.getCellStyle().getDataFormat(), c.getCellStyle().getDataFormatString());
                    case STRING -> c.getStringCellValue();
                    case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
                    default -> "";
                };
            } catch (RuntimeException e) {
                return "";
            }
        }

        private String get(List<String> textos, Coluna col) {
            Integer i = colunas == null ? null : colunas.get(col);
            return (i == null || i >= textos.size()) ? "" : textos.get(i);
        }

        /** Numérico: usa o valor bruto da célula quando é número (evita problemas de formatação). */
        private BigDecimal numero(Row row, Coluna col, List<String> textos) {
            Integer i = colunas.get(col);
            if (i == null) return null;
            Cell c = row.getCell(i);
            boolean numerico = c != null && (c.getCellType() == CellType.NUMERIC
                    || (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC));
            if (numerico) {
                // O ERP grava alguns números com resíduo de ponto flutuante (41.3600006103...): arredonda.
                return BigDecimal.valueOf(c.getNumericCellValue()).setScale(3, java.math.RoundingMode.HALF_UP);
            }
            return TextoUtils.numero(get(textos, col));
        }

        private String valorAposCelula(List<String> textos, int i) {
            for (int j = i + 1; j < textos.size(); j++) if (!textos.get(j).isBlank()) return textos.get(j);
            return null;
        }

        /** Próxima célula preenchida, desde que não seja outro rótulo ("Data Embarque:", "Rota:"...). */
        private String valorAposCelulaSeNaoRotulo(List<String> textos, int i) {
            String v = valorAposCelula(textos, i);
            return (v == null || v.trim().endsWith(":") || normalizar(v).matches("^(DATA EMBARQUE|ROTA|MOTORISTA|VEICULO).*"))
                    ? null : v;
        }

        private String primeiroNumeroAte(List<String> textos, int colunaMax) {
            for (int j = 0; j <= Math.min(colunaMax, textos.size() - 1); j++) {
                if (textos.get(j).trim().matches("\\d+")) return textos.get(j).trim();
            }
            return null;
        }

        private String vazioParaNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }
}
