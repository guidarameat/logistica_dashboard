package br.com.guidara.expedicao.importacao.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.*;

import static br.com.guidara.expedicao.importacao.TextoUtils.normalizar;

/**
 * Soma todas as colunas numéricas da planilha (Valor, Frete, Peso, Qtde, Qtde Faturada...),
 * considerando só as linhas que viraram pedido. Colunas de código (Pedido, OC, CEP, CFOP...),
 * datas e percentuais ficam de fora, pois somá-las não faz sentido.
 *
 * Se a planilha tiver uma linha de total (como o rodapé azul do ERP), os valores dela são
 * guardados para conferir com a soma calculada.
 */
class SomadorColunas {

    /** Títulos (normalizados) que são identificadores/códigos, não quantidades. */
    private static final Set<String> NAO_SOMAVEIS = Set.of(
            "FIL", "FILIAL", "PEDIDO", "PEDIDO CLIENTE", "NO PEDIDO", "NOTA", "NF", "NOTA FISCAL",
            "OC", "NO OC", "CLIENTE", "CEP", "NR", "NRO", "NO", "NUMERO", "CFOP", "LOCALIDADE");

    private static final BigDecimal TOLERANCIA = new BigDecimal("0.01");

    private final Map<String, BigDecimal> somas = new LinkedHashMap<>();
    private final Map<String, BigDecimal> linhaDeTotal = new LinkedHashMap<>();
    private List<String> titulos = List.of();

    void definirTitulos(List<String> titulos) {
        this.titulos = List.copyOf(titulos);
    }

    void somarLinha(Row row) {
        percorrer(row, (titulo, valor) -> somas.merge(titulo, valor, BigDecimal::add));
    }

    void registrarLinhaDeTotal(Row row) {
        percorrer(row, (titulo, valor) -> linhaDeTotal.merge(titulo, valor, BigDecimal::add));
    }

    /**
     * Totais na ordem das colunas. Uma coluna em branco em todos os pedidos, mas numérica na linha
     * de total (ex.: "Qtde Faturada" = 0,00), aparece com total zero.
     */
    Map<String, BigDecimal> somas() {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String t : titulos) {
            String titulo = t.trim();
            if (somas.containsKey(titulo) || linhaDeTotal.containsKey(titulo)) {
                out.put(titulo, somas.getOrDefault(titulo, BigDecimal.ZERO));
            }
        }
        somas.forEach(out::putIfAbsent); // colunas de outras abas com títulos diferentes
        return Collections.unmodifiableMap(out);
    }

    /** Compara a soma calculada com a linha de total da planilha (quando existe). */
    List<String> conferencia() {
        if (linhaDeTotal.isEmpty()) return List.of();
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("pt", "BR"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(3);
        List<String> divergencias = new ArrayList<>();
        linhaDeTotal.forEach((titulo, totalPlanilha) -> {
            BigDecimal calculado = somas.getOrDefault(titulo, BigDecimal.ZERO);
            if (calculado.subtract(totalPlanilha).abs().compareTo(TOLERANCIA) > 0) {
                divergencias.add("Conferência: coluna \"" + titulo + "\" somou " + nf.format(calculado)
                        + ", mas a linha de total da planilha mostra " + nf.format(totalPlanilha) + ".");
            }
        });
        return divergencias.isEmpty()
                ? List.of("Conferência com a linha de total da planilha: todos os totais batem.")
                : divergencias;
    }

    private void percorrer(Row row, java.util.function.BiConsumer<String, BigDecimal> acao) {
        for (int i = 0; i < titulos.size(); i++) {
            String titulo = titulos.get(i).trim();
            if (!somavel(titulo)) continue;
            Cell c = row.getCell(i);
            if (c == null) continue;
            boolean numerico = c.getCellType() == CellType.NUMERIC
                    || (c.getCellType() == CellType.FORMULA && c.getCachedFormulaResultType() == CellType.NUMERIC);
            if (!numerico || DateUtil.isCellDateFormatted(c)) continue; // datas são números no Excel
            acao.accept(titulo, BigDecimal.valueOf(c.getNumericCellValue()).setScale(3, RoundingMode.HALF_UP));
        }
    }

    private static boolean somavel(String titulo) {
        String t = normalizar(titulo).replace(":", "").replace(".", "").trim();
        if (t.isEmpty() || NAO_SOMAVEIS.contains(t)) return false;
        return !(t.startsWith("COD") || t.startsWith("PERC") || t.contains("%") || t.startsWith("DATA")
                || t.equals("EMISSAO") || t.equals("EMBARQUE"));
    }
}
