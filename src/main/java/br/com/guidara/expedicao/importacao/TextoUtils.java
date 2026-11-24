package br.com.guidara.expedicao.importacao;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextoUtils {

    /** Placa antiga (ABC-1234) e Mercosul (ABC1D23), com ou sem hífen. Ex.: PUV-0E30, FQZ-5B92, QNJ-5F12. */
    public static final Pattern PLACA = Pattern.compile("\\b([A-Z]{3})-?(\\d[A-Z0-9]\\d{2})\\b");
    public static final Pattern DATA = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})");
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private TextoUtils() {}

    /** Maiúsculas, sem acento, sem espaços duplicados. "Nº OC:" -> "NO OC:" */
    public static String normalizar(String s) {
        if (s == null) return "";
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.replace('º', 'O').replace('°', 'O')
                .toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Devolve a placa no formato AAA-0X00. */
    public static Optional<String> extrairPlaca(String texto) {
        Matcher m = PLACA.matcher(normalizar(texto));
        return m.find() ? Optional.of(m.group(1) + "-" + m.group(2)) : Optional.empty();
    }

    public static Optional<LocalDate> extrairData(String texto) {
        Matcher m = DATA.matcher(texto == null ? "" : texto);
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(m.group(1), DDMMYYYY));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Converte "1.234,56", "257,10" ou "14.72" em BigDecimal. Vazio/inválido -> null. */
    public static BigDecimal numero(String s) {
        if (s == null) return null;
        String t = s.trim().replace("R$", "").replace(" ", "");
        if (t.isEmpty()) return null;
        if (t.contains(",")) t = t.replace(".", "").replace(',', '.');
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Texto (normalizado) após um rótulo já normalizado. "Motorista: Joao" -> "JOAO". */
    public static String depoisDoRotulo(String texto, String rotulo) {
        String norm = normalizar(texto);
        int i = norm.indexOf(rotulo);
        if (i < 0) return "";
        return norm.substring(i + rotulo.length()).replaceFirst("^[:\\s-]+", "").trim();
    }
}
