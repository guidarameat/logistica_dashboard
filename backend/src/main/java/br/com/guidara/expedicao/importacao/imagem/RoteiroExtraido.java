package br.com.guidara.expedicao.importacao.imagem;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Formato JSON que a OpenAI devolve ao ler a imagem (deve bater com o SCHEMA em ImagemRoteiroImporter).
 */
public record RoteiroExtraido(
        @JsonPropertyDescription("Um item por bloco 'Nº OC' / veículo visível na imagem")
        List<CargaExtraida> cargas,
        @JsonPropertyDescription("Trechos ilegíveis, cortados ou duvidosos. Lista vazia se tudo foi lido com clareza")
        List<String> observacoesLeitura
) {

    public record CargaExtraida(
            @JsonPropertyDescription("Número da OC (ex.: 8261)") String numeroOc,
            @JsonPropertyDescription("Placa do veículo exatamente como impressa (ex.: PUV-0E30)") String placa,
            @JsonPropertyDescription("Nome do motorista; vazio se não informado") String motorista,
            @JsonPropertyDescription("Data de embarque no formato dd/MM/yyyy") String dataEmbarque,
            @JsonPropertyDescription("Texto da rota; vazio se não informado") String rota,
            @JsonPropertyDescription("Valor de 'Peso Total Bruto' no rodapé do relatório") Double pesoTotalBruto,
            @JsonPropertyDescription("Valor de 'Peso Total Líquido' no rodapé do relatório") Double pesoTotalLiquido,
            List<PedidoExtraido> pedidos
    ) {}

    public record PedidoExtraido(
            String filial,
            String pedido,
            @JsonPropertyDescription("Coluna Nota, exatamente como impressa. '0' quando não faturado") String nota,
            @JsonPropertyDescription("Coluna Cliente completa, incluindo o código (ex.: '12696 - ESCOLA BEIT YAACOV')") String cliente,
            String cep,
            String endereco,
            @JsonPropertyDescription("Coluna Nr") String numero,
            String bairro,
            String cidade,
            @JsonPropertyDescription("Peso líquido como número decimal com ponto (257,10 -> 257.10)") Double pesoLiquido,
            @JsonPropertyDescription("Volumes como número decimal com ponto") Double volumes,
            String observacoes,
            @JsonPropertyDescription("true se a linha estiver grifada/destacada com fundo amarelo ou laranja-claro; false se o fundo for branco ou cinza")
            boolean destacadoAmarelo
    ) {}
}
