package br.com.guidara.expedicao.importacao.excel;

import br.com.guidara.expedicao.config.ImportacaoProperties;
import br.com.guidara.expedicao.domain.Carga;
import br.com.guidara.expedicao.domain.StatusDoca;
import br.com.guidara.expedicao.domain.StatusPedido;
import br.com.guidara.expedicao.importacao.ImportadorRoteiro.ResultadoLeitura;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Monta em memória uma planilha no layout do "Relatório Roteiro Entrega" (OC 8263). */
class ExcelRoteiroImporterTest {

    private final ImportacaoProperties props = new ImportacaoProperties(
            new ImportacaoProperties.Importacao(new ImportacaoProperties.Amarelo(35, 65, 0.15f, 0.70f), 0.5),
            new ImportacaoProperties.Visao(false, "gpt-4o", 16000));

    private final ExcelRoteiroImporter importer = new ExcelRoteiroImporter(new DetectorCorAmarela(props), props);

    @Test
    void separaAmarelosNaoFaturadosEPlaca() throws Exception {
        ResultadoLeitura r = importer.ler(planilha(), "roteiro.xlsx", null);

        assertEquals(1, r.cargas().size());
        Carga c = r.cargas().get(0);
        assertEquals("8263", c.numeroOc());
        assertEquals("QNJ-5F12", c.placa());
        assertEquals(LocalDate.of(2026, 11, 18), c.dataEmbarque());
        assertEquals(4, c.pedidos().size());

        assertEquals(StatusPedido.FATURADO, c.pedidos().get(0).status());              // nota 59357
        assertEquals(StatusPedido.PENDENTE_FATURAMENTO, c.pedidos().get(1).status());  // nota 0, branco
        assertEquals(StatusPedido.NAO_ENVIADO, c.pedidos().get(2).status());           // amarelo
        assertEquals(StatusPedido.NAO_ENVIADO, c.pedidos().get(3).status());           // amarelo (tom claro)
        assertEquals(0, c.pedidos().get(0).pesoLiquido().compareTo(new java.math.BigDecimal("14.72")));
        assertEquals(StatusDoca.EM_DOCA, c.statusDoca());
        // Peso bruto = maior valor do rodapé (líquido 428,364 > bruto 319,140)
        assertEquals(0, c.pesoBrutoTotal().compareTo(new java.math.BigDecimal("428.364")));
    }

    /** Exportação "PEDIDOS" do ERP: tabela plana com linha de totais no final (contagem na coluna Pedido). */
    @Test
    void ignoraLinhaDeTotalDaExportacaoDoErp() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Planilha1");
            linha(s, 0, null, "Pedido", "Embarque", "Valor", "OC", "Cliente", "Razão Social", "CEP", "Endereço", "Nro", "Bairro", "Cidade", "Peso", "Qtde");
            Object[][] dados = {
                    {108670d, "19/11/2026", 4030.22, "", 10017d, "PRANDINI BAR E RESTAURANTE LTDA", "02143060", "RUA OSAKA", "880", "JARDIM JAPAO", "SAO PAULO", 41.36, 3d},
                    {109562d, "19/11/2026", 2216.36, 8270d, 9726d, "V M PREMIUM BEEF LTDA", "13565251", "AV MIGUEL DAMHA", "009", "PQ TECNOL", "SAO CARLOS", 39.60, 4d},
                    // linha azul de totais: Pedido = quantidade de pedidos, Valor/Peso/Qtde = somas
                    {2d, "", 6246.58, "", "", "", "", "", "", "", "", 80.96, 7d}};
            for (int r = 0; r < dados.length; r++) {
                Row row = s.createRow(r + 1);
                for (int c = 0; c < dados[r].length; c++) {
                    Cell cell = row.createCell(c);
                    if (dados[r][c] instanceof Double d) cell.setCellValue(d); else cell.setCellValue((String) dados[r][c]);
                }
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }

        ResultadoLeitura r = importer.ler(xlsx, "PEDIDOS 19-11.xlsx", null);
        List<br.com.guidara.expedicao.domain.Pedido> pedidos = r.cargas().stream().flatMap(c -> c.pedidos().stream()).toList();

        assertEquals(2, pedidos.size(), "a linha de total não pode virar pedido");
        assertEquals(0, pedidos.stream().map(br.com.guidara.expedicao.domain.Pedido::valor)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add).compareTo(new java.math.BigDecimal("6246.58")));
        assertEquals("10017 - PRANDINI BAR E RESTAURANTE LTDA", pedidos.get(0).cliente());
        assertEquals(0, pedidos.get(0).volumes().compareTo(new java.math.BigDecimal("3")));

        // Totais de todas as colunas numéricas, sem códigos (Pedido, OC, Cliente, CEP...)
        assertEquals(List.of("Valor", "Peso", "Qtde"), List.copyOf(r.totais().keySet()));
        assertEquals(0, r.totais().get("Peso").compareTo(new java.math.BigDecimal("80.96")));
        assertTrue(r.avisos().contains("Conferência com a linha de total da planilha: todos os totais batem."));
    }

    private byte[] planilha() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Roteiro");
            XSSFCellStyle amarelo = estilo(wb, 0xFF, 0xD9, 0x66);      // laranja-claro do relatório
            XSSFCellStyle amareloClaro = estilo(wb, 0xFF, 0xF2, 0xCC); // amarelo bem claro

            linha(s, 0, null, "Nº OC:", "", "Veículo:  QNJ-5F12", "", "Data Embarque: 18/11/2026");
            linha(s, 1, null, "8263", "", "Motorista:", "", "Rota: 0 -");
            linha(s, 2, null, "Fil", "Pedido", "Nota", "Cliente", "CEP", "Endereço", "Nr", "Bairro", "Cidade", "Peso Líquido", "Volumes", "Observações");
            linhaPedido(s, 3, null, "110796", 59357, "14308 - FIFTY FIFTY COCKTAIL BAR E", 14.72, 1);
            linhaPedido(s, 4, null, "110882", 0, "6198 - HORTUS COMERCIO DE ALIMENTOS S.A.", 36.55, 2);
            linhaPedido(s, 5, amarelo, "110897", 0, "15172 - G L M RIBEIRO JORGE LTDA", 57.68, 0);
            linhaPedido(s, 6, amareloClaro, "110898", 0, "14070 - PEDRO BAIERL LIPPI", 56.45, 0);
            linha(s, 7, null, "Total de Entregas --> 4", "", "Peso Total Líquido:", "428,364", "", "Peso Total Bruto:", "319,140", "Volumes: 23");

            wb.write(out);
            return out.toByteArray();
        }
    }

    private static XSSFCellStyle estilo(XSSFWorkbook wb, int r, int g, int b) {
        XSSFCellStyle st = wb.createCellStyle();
        st.setFillForegroundColor(new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null));
        st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return st;
    }

    private static void linha(Sheet s, int idx, CellStyle st, String... valores) {
        Row row = s.createRow(idx);
        for (int i = 0; i < valores.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(valores[i]);
            if (st != null) c.setCellStyle(st);
        }
    }

    private static void linhaPedido(Sheet s, int idx, CellStyle st, String pedido, int nota, String cliente,
                                    double peso, double volumes) {
        Row row = s.createRow(idx);
        Object[] v = {"1", pedido, nota, cliente, "05418001", "RUA X", "10", "PINHEIROS", "SAO PAULO", peso, volumes};
        for (int i = 0; i < v.length; i++) {
            Cell c = row.createCell(i);
            if (v[i] instanceof Number n) c.setCellValue(n.doubleValue());
            else c.setCellValue((String) v[i]);
            if (st != null) c.setCellStyle(st);
        }
    }
}
