package br.com.guidara.expedicao.importacao.excel;

import br.com.guidara.expedicao.config.ImportacaoProperties;
import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Color;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.springframework.stereotype.Component;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Detecta preenchimento amarelo em células do Excel (.xlsx e .xls).
 *
 * Em vez de comparar com um único código (FFFF00), converte a cor para HSB e aceita
 * uma faixa de matiz/saturação/brilho: assim pega o amarelo "puro", o amarelo claro
 * (FFF2CC, FFE699), o laranja-claro do relatório (FFD966/FFC000) e cores de tema com tint.
 *
 * Eficiência: o resultado é cacheado por CellStyle. Uma planilha tem poucos estilos
 * (dezenas) e milhares de células, então cada estilo é avaliado uma única vez.
 * Use uma instância de {@link Sessao} por workbook.
 */
@Component
public class DetectorCorAmarela {

    private final ImportacaoProperties.Amarelo cfg;

    public DetectorCorAmarela(ImportacaoProperties props) {
        this.cfg = props.importacao().amarelo();
    }

    public Sessao novaSessao() {
        return new Sessao();
    }

    public final class Sessao {
        private final Map<CellStyle, Boolean> cachePorEstilo = new IdentityHashMap<>();

        public boolean ehAmarela(Cell cell) {
            if (cell == null) return false;
            CellStyle style = cell.getCellStyle();
            if (style == null) return false;
            return cachePorEstilo.computeIfAbsent(style, DetectorCorAmarela.this::estiloEhAmarelo);
        }
    }

    private boolean estiloEhAmarelo(CellStyle style) {
        if (style.getFillPattern() == null || style.getFillPattern() == FillPatternType.NO_FILL) {
            return false;
        }
        // Com SOLID_FOREGROUND a cor visível é a "foreground"; em padrões hachurados checamos as duas.
        return corEhAmarela(style.getFillForegroundColorColor())
                || (style.getFillPattern() != FillPatternType.SOLID_FOREGROUND
                    && corEhAmarela(style.getFillBackgroundColorColor()));
    }

    private boolean corEhAmarela(Color cor) {
        int[] rgb = paraRgb(cor);
        return rgb != null && rgbEhAmarelo(rgb[0], rgb[1], rgb[2]);
    }

    /** Também usado na conferência de imagens. */
    public boolean rgbEhAmarelo(int r, int g, int b) {
        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
        float hueGraus = hsb[0] * 360f;
        return hueGraus >= cfg.hueMin() && hueGraus <= cfg.hueMax()
                && hsb[1] >= cfg.saturacaoMin()
                && hsb[2] >= cfg.brilhoMin();
    }

    private static int[] paraRgb(Color cor) {
        if (cor instanceof XSSFColor x) {
            if (x.isAuto()) return null;
            // getRGBWithTint aplica o "tint" de cores de tema (ex.: "Amarelo, Ênfase 4, 60% mais claro")
            byte[] b = x.getRGBWithTint();
            if (b == null) b = x.getRGB();
            if (b == null || b.length < 3) return null;
            int off = b.length == 4 ? 1 : 0; // ARGB
            return new int[]{b[off] & 0xFF, b[off + 1] & 0xFF, b[off + 2] & 0xFF};
        }
        if (cor instanceof HSSFColor h) {
            // .xls: a cor vem da paleta do workbook (inclui paleta customizada)
            short[] t = h.getTriplet();
            if (t == null || t.length < 3) return null;
            return new int[]{t[0], t[1], t[2]};
        }
        return null;
    }
}
