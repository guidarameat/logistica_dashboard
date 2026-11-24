package br.com.guidara.expedicao.service;

import br.com.guidara.expedicao.domain.*;
import br.com.guidara.expedicao.dto.DashboardDtos.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Regras de negócio do painel:
 *  - Pedido NÃO ENVIADO      = linha grifada em amarelo.
 *  - Pedido FATURADO         = enviado e com Nota diferente de 0.
 *  - PENDENTE DE FATURAMENTO = enviado e com Nota = 0.
 *  - Veículo FORA DA DOCA    = todos os pedidos enviados da OC faturados (ou ajuste manual).
 */
@Service
public class DashboardService {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);

    private final ImportacaoService importacaoService;

    public DashboardService(ImportacaoService importacaoService) {
        this.importacaoService = importacaoService;
    }

    public DashboardResumo montar(UUID importacaoId) {
        Importacao imp = importacaoService.obter(importacaoId);
        List<Carga> cargas = imp.cargas();
        List<PedidoView> todos = pedidos(imp, null, null, null);

        // ---- pedidos ----
        long programados = todos.size();
        long naoEnviados = contar(todos, p -> p.status() == StatusPedido.NAO_ENVIADO);
        long faturados = contar(todos, p -> p.status() == StatusPedido.FATURADO);
        long pendentes = contar(todos, p -> p.status() == StatusPedido.PENDENTE_FATURAMENTO);
        Pedidos pedidos = new Pedidos(programados, programados - naoEnviados, naoEnviados, faturados, pendentes,
                somar(cargas.stream(), Carga::pesoBrutoTotal), // maior peso do rodapé de cada OC
                somar(todos.stream().filter(PedidoView::destacadoAmarelo), PedidoView::pesoLiquido),
                somar(todos.stream(), PedidoView::volumes));

        // ---- financeiro (só se houver coluna de valor) ----
        boolean temValor = todos.stream().anyMatch(p -> p.valor() != null);
        Financeiro financeiro = null;
        if (temValor) {
            BigDecimal previsto = somar(todos.stream().filter(p -> !p.destacadoAmarelo()), PedidoView::valor);
            BigDecimal faturado = somar(todos.stream().filter(p -> p.status() == StatusPedido.FATURADO), PedidoView::valor);
            BigDecimal naoEnviado = somar(todos.stream().filter(PedidoView::destacadoAmarelo), PedidoView::valor);
            financeiro = new Financeiro(previsto, faturado, previsto.subtract(faturado), naoEnviado);
        }

        // ---- veículos (pedidos sem OC/placa não estão em veículo nenhum) ----
        List<Carga> comVeiculo = cargas.stream().filter(Carga::temVeiculo).toList();
        List<String> emDoca = placas(comVeiculo, StatusDoca.EM_DOCA);
        List<String> foraDoca = placas(comVeiculo, StatusDoca.FORA_DA_DOCA);
        Veiculos veiculos = new Veiculos(comVeiculo.size(), foraDoca.size(), emDoca.size(), emDoca, foraDoca);

        // ---- indicadores ----
        Indicadores indicadores = new Indicadores(
                financeiro == null ? null : pct(financeiro.faturado(), financeiro.previsto()),
                pct(faturados, programados),
                pct(naoEnviados, programados),
                financeiro != null ? pct(financeiro.pendente(), financeiro.previsto()) : pct(pendentes, programados - naoEnviados),
                pct(emDoca.size(), comVeiculo.size()));

        List<PedidoView> listaNaoEnviados = filtrar(todos, StatusPedido.NAO_ENVIADO);
        List<PedidoView> listaPendentes = filtrar(todos, StatusPedido.PENDENTE_FATURAMENTO);

        return new DashboardResumo(
                imp.id(), imp.nomeArquivo(), imp.dataHora(), dataEmbarque(cargas),
                financeiro, pedidos, veiculos, indicadores,
                cargas.stream().map(this::resumir).toList(),
                listaNaoEnviados, listaPendentes,
                alertas(cargas, pedidos, listaPendentes),
                imp.avisos(),
                imp.totaisPlanilha().entrySet().stream()
                        .map(e -> new TotalColuna(e.getKey(), e.getValue(), monetario(e.getKey())))
                        .toList());
    }

    private static boolean monetario(String coluna) {
        String c = coluna.toUpperCase(Locale.ROOT);
        return c.startsWith("VALOR") || c.startsWith("VLR") || c.startsWith("FRETE") || c.contains("R$");
    }

    /** Lista de pedidos com filtros opcionais (status, placa, OC). */
    public List<PedidoView> pedidos(UUID importacaoId, StatusPedido status, String placa, String oc) {
        return pedidos(importacaoService.obter(importacaoId), status, placa, oc);
    }

    public List<CargaResumo> cargas(UUID importacaoId) {
        return importacaoService.obter(importacaoId).cargas().stream().map(this::resumir).toList();
    }

    // ------------------------------------------------------------------

    private List<PedidoView> pedidos(Importacao imp, StatusPedido status, String placa, String oc) {
        List<PedidoView> out = new ArrayList<>();
        for (Carga c : imp.cargas()) {
            if (placa != null && !placa.equalsIgnoreCase(c.placa())) continue;
            if (oc != null && !oc.equals(c.numeroOc())) continue;
            for (Pedido p : c.pedidos()) {
                if (status != null && p.status() != status) continue;
                out.add(new PedidoView(c.numeroOc(), c.placa(), p.numeroPedido(), p.numeroNota(), p.cliente(),
                        p.cep(), p.endereco(), p.numero(), p.bairro(), p.cidade(), p.pesoLiquido(), p.volumes(),
                        p.observacoes(), p.valor(), p.status(), p.destacadoAmarelo()));
            }
        }
        return out;
    }

    private CargaResumo resumir(Carga c) {
        List<Pedido> ps = c.pedidos();
        return new CargaResumo(
                c.numeroOc(), c.placa(), c.motorista(), c.dataEmbarque(), c.statusDoca(), c.statusDocaManual() != null,
                ps.size(),
                ps.stream().filter(Pedido::enviado).count(),
                ps.stream().filter(p -> !p.enviado()).count(),
                ps.stream().filter(p -> p.status() == StatusPedido.FATURADO).count(),
                ps.stream().filter(p -> p.status() == StatusPedido.PENDENTE_FATURAMENTO).count(),
                c.pesoBrutoTotal(),
                somar(ps.stream(), Pedido::volumes),
                ps.stream().anyMatch(p -> p.valor() != null) ? somar(ps.stream().filter(Pedido::enviado), Pedido::valor) : null);
    }

    private List<String> alertas(List<Carga> cargas, Pedidos pedidos, List<PedidoView> pendentes) {
        List<String> alertas = new ArrayList<>();
        cargas.stream().filter(c -> c.temVeiculo() && c.pesoBrutoTotal() == null).forEach(c -> alertas.add(
                (c.numeroOc() == null ? "Carga sem OC" : "OC " + c.numeroOc())
                        + " sem peso no relatório: não entrou no peso bruto total."));
        if (pedidos.naoEnviados() > 0) {
            alertas.add(pedidos.naoEnviados() + " pedido(s) grifado(s) em amarelo NÃO serão enviados ("
                    + pedidos.pesoLiquidoNaoEnviado().setScale(2, RoundingMode.HALF_UP) + " kg).");
        }
        for (Carga c : cargas) {
            if (!c.temVeiculo()) {
                alertas.add(c.pedidos().size() + " pedido(s) sem OC: ainda não alocados em nenhum veículo.");
                continue;
            }
            long pend = c.pedidos().stream().filter(p -> p.status() == StatusPedido.PENDENTE_FATURAMENTO).count();
            if (c.statusDoca() == StatusDoca.EM_DOCA && pend > 0) {
                alertas.add("Veículo " + Objects.toString(c.placa(), "s/ placa") + " ("
                        + (c.numeroOc() == null ? "sem OC" : "OC " + c.numeroOc())
                        + ") em doca com " + pend + " pedido(s) sem nota.");
            }
            if (c.statusDoca() == StatusDoca.FORA_DA_DOCA && pend > 0) {
                alertas.add("ATENÇÃO: veículo " + c.placa() + " marcado fora da doca, mas com " + pend + " pedido(s) sem nota.");
            }
        }
        if (pendentes.isEmpty() && pedidos.programados() > 0) {
            alertas.add("Todos os pedidos enviados estão faturados.");
        }
        return alertas;
    }

    private static List<PedidoView> filtrar(List<PedidoView> todos, StatusPedido s) {
        return todos.stream().filter(p -> p.status() == s).toList();
    }

    private static List<String> placas(List<Carga> cargas, StatusDoca s) {
        return cargas.stream().filter(c -> c.statusDoca() == s)
                .map(c -> Objects.toString(c.placa(), "OC " + c.numeroOc())).toList();
    }

    private static LocalDate dataEmbarque(List<Carga> cargas) {
        return cargas.stream().map(Carga::dataEmbarque).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    }

    private static <T> long contar(List<T> l, Predicate<T> p) {
        return l.stream().filter(p).count();
    }

    private static <T> BigDecimal somar(Stream<T> s, Function<T, BigDecimal> f) {
        return s.map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal pct(long parte, long total) {
        return pct(BigDecimal.valueOf(parte), BigDecimal.valueOf(total));
    }

    private static BigDecimal pct(BigDecimal parte, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return parte.multiply(CEM).divide(total, 2, RoundingMode.HALF_UP);
    }
}
