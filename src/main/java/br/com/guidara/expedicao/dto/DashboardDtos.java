package br.com.guidara.expedicao.dto;

import br.com.guidara.expedicao.domain.StatusDoca;
import br.com.guidara.expedicao.domain.StatusPedido;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** DTOs de saída da API (agrupados num arquivo para facilitar a leitura). */
public final class DashboardDtos {

    private DashboardDtos() {}

    public record DashboardResumo(
            UUID importacaoId,
            String arquivo,
            LocalDateTime importadoEm,
            LocalDate dataEmbarque,
            Financeiro financeiro,         // null quando a planilha não tem coluna de valor
            Pedidos pedidos,
            Veiculos veiculos,
            Indicadores indicadores,
            List<CargaResumo> cargas,
            List<PedidoView> pedidosNaoEnviados,
            List<PedidoView> pedidosPendentesFaturamento,
            List<String> alertas,
            List<String> avisosImportacao,
            List<TotalColuna> totaisPlanilha
    ) {}

    /** Soma de uma coluna numérica da planilha. monetario = exibir em R$. */
    public record TotalColuna(String coluna, BigDecimal total, boolean monetario) {}

    public record Financeiro(BigDecimal previsto, BigDecimal faturado, BigDecimal pendente, BigDecimal naoEnviado) {}

    public record Pedidos(
            long programados,
            long enviados,
            long naoEnviados,          // grifados em amarelo
            long faturados,
            long pendentesFaturamento, // enviados com Nota = 0
            BigDecimal pesoBrutoTotal,         // soma do "Peso Total Bruto" das OCs
            BigDecimal pesoLiquidoNaoEnviado,
            BigDecimal volumesTotais
    ) {}

    public record Veiculos(long programados, long foraDaDoca, long emDoca, List<String> placasEmDoca, List<String> placasForaDaDoca) {}

    /** Percentuais 0-100 com 2 casas. */
    public record Indicadores(
            BigDecimal execucaoFinanceira,     // faturado / previsto (null sem coluna de valor)
            BigDecimal pedidosFaturados,       // faturados / programados
            BigDecimal pedidosNaoEnviados,     // amarelos / programados
            BigDecimal faturamentoPendente,    // pendente R$ / previsto R$ (ou pedidos, sem valor)
            BigDecimal frotaEmDoca             // veículos em doca / programados
    ) {}

    public record CargaResumo(
            String numeroOc,
            String placa,
            String motorista,
            LocalDate dataEmbarque,
            StatusDoca statusDoca,
            boolean statusDocaManual,
            long totalPedidos,
            long enviados,
            long naoEnviados,
            long faturados,
            long pendentesFaturamento,
            BigDecimal pesoBruto,              // "Peso Total Bruto" do rodapé da OC; null se ausente
            BigDecimal volumes,
            BigDecimal valor
    ) {}

    public record PedidoView(
            String numeroOc,
            String placa,
            String pedido,
            String nota,
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
            StatusPedido status,
            boolean destacadoAmarelo
    ) {}

    public record ImportacaoResumo(UUID id, String arquivo, String origem, LocalDateTime dataHora,
                                   int cargas, long pedidos, List<String> avisos) {}

    public record AlterarStatusDocaRequest(StatusDoca status) {}
}
