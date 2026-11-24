package br.com.guidara.expedicao.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Cabeçalho de um roteiro (uma OC = um veículo) e seus pedidos.
 *
 * @param pesoBrutoTotal   peso bruto da OC em kg, calculado por {@link #pesoBrutoConsiderado}
 * @param statusDocaManual status informado manualmente pela operação; null = calculado pela regra
 */
public record Carga(
        String numeroOc,
        String placa,
        String motorista,
        LocalDate dataEmbarque,
        String rota,
        List<Pedido> pedidos,
        BigDecimal pesoBrutoTotal,
        StatusDoca statusDocaManual
) {

    public Carga {
        pedidos = pedidos == null ? List.of() : List.copyOf(pedidos);
    }

    /**
     * Regra do peso bruto da OC: usa o MAIOR valor entre o "Peso Total Bruto" e o "Peso Total Líquido"
     * do rodapé (no relatório eles às vezes vêm invertidos) e, sem rodapé, a soma do peso líquido das linhas.
     */
    public static BigDecimal pesoBrutoConsiderado(BigDecimal brutoRodape, BigDecimal liquidoRodape, List<Pedido> pedidos) {
        BigDecimal somaLinhas = pedidos == null ? null : pedidos.stream()
                .map(Pedido::pesoLiquido).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal::add).orElse(null);
        return java.util.stream.Stream.of(brutoRodape, liquidoRodape, somaLinhas)
                .filter(java.util.Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * Só é um veículo de verdade quando tem OC ou placa. Na exportação de pedidos do ERP, os pedidos
     * com a coluna OC vazia ainda não foram alocados em nenhuma carga e caem num grupo sem OC/placa.
     */
    public boolean temVeiculo() {
        return numeroOc != null || placa != null;
    }

    public Carga comStatusDoca(StatusDoca status) {
        return new Carga(numeroOc, placa, motorista, dataEmbarque, rota, pedidos, pesoBrutoTotal, status);
    }

    /**
     * Regra padrão (quando não há status manual): o veículo só sai da doca quando
     * todos os pedidos que vão nele (os não amarelos) estão faturados.
     */
    public StatusDoca statusDoca() {
        if (statusDocaManual != null) return statusDocaManual;
        boolean tudoFaturado = pedidos.stream().filter(Pedido::enviado).allMatch(Pedido::faturado);
        boolean temPedidoEnviado = pedidos.stream().anyMatch(Pedido::enviado);
        return temPedidoEnviado && tudoFaturado ? StatusDoca.FORA_DA_DOCA : StatusDoca.EM_DOCA;
    }
}
