package br.com.guidara.expedicao.domain;

import java.math.BigDecimal;

/**
 * Uma linha do "Relatório Roteiro Entrega".
 * Colunas: Fil | Pedido | Nota | Cliente | CEP | Endereço | Nr | Bairro | Cidade | Peso Líquido | Volumes | Observações
 *
 * @param valor             valor do pedido em R$ (opcional: só existe se a planilha tiver uma coluna "Valor")
 * @param qtdeFaturada      coluna "Qtde Faturada" da exportação de pedidos do ERP (opcional)
 * @param destacadoAmarelo  true quando a linha está grifada em amarelo (pedido NÃO enviado)
 */
public record Pedido(
        String filial,
        String numeroPedido,
        String numeroNota,
        String cliente,
        String cep,
        String endereco,
        String numero,
        String bairro,
        String cidade,
        BigDecimal pesoLiquido,
        BigDecimal volumes,
        String observacoes,
        BigDecimal valor,
        BigDecimal qtdeFaturada,
        boolean destacadoAmarelo
) {

    /**
     * Regra: pedido faturado = tem nota fiscal diferente de 0 (relatório de roteiro)
     * OU tem "Qtde Faturada" maior que 0 (exportação de pedidos do ERP).
     */
    public boolean faturado() {
        if (qtdeFaturada != null && qtdeFaturada.signum() > 0) return true;
        if (numeroNota == null) return false;
        String n = numeroNota.trim();
        return !n.isEmpty() && !n.matches("0+");
    }

    /** Regra: pedido grifado de amarelo não é enviado. */
    public boolean enviado() {
        return !destacadoAmarelo;
    }

    public StatusPedido status() {
        if (destacadoAmarelo) return StatusPedido.NAO_ENVIADO;
        return faturado() ? StatusPedido.FATURADO : StatusPedido.PENDENTE_FATURAMENTO;
    }
}
