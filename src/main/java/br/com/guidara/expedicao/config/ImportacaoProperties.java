package br.com.guidara.expedicao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "guidara")
public record ImportacaoProperties(Importacao importacao, Visao visao) {

    public record Importacao(Amarelo amarelo, double fracaoLinhaAmarela) {}

    public record Amarelo(float hueMin, float hueMax, float saturacaoMin, float brilhoMin) {}

    public record Visao(boolean habilitada, String modelo, long maxTokens) {}
}
