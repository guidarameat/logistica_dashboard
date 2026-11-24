package br.com.guidara.expedicao.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resultado de um upload (Excel ou imagem). */
public record Importacao(
        UUID id,
        String nomeArquivo,
        OrigemImportacao origem,
        LocalDateTime dataHora,
        List<Carga> cargas,
        List<String> avisos,
        Map<String, BigDecimal> totaisPlanilha   // soma de cada coluna numérica (ordem das colunas)
) {
    public enum OrigemImportacao { EXCEL, IMAGEM }

    public Importacao {
        cargas = cargas == null ? List.of() : List.copyOf(cargas);
        avisos = avisos == null ? List.of() : List.copyOf(avisos);
        totaisPlanilha = totaisPlanilha == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(totaisPlanilha));
    }
}
