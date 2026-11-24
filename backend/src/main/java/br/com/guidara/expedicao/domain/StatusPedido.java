package br.com.guidara.expedicao.domain;

public enum StatusPedido {
    /** Enviado e com nota fiscal emitida. */
    FATURADO,
    /** Enviado (não está em amarelo), mas ainda com Nota = 0. */
    PENDENTE_FATURAMENTO,
    /** Grifado em amarelo no relatório: retirado da carga / não enviado. */
    NAO_ENVIADO
}
