package br.com.guidara.expedicao.controller;

import br.com.guidara.expedicao.domain.Importacao;
import br.com.guidara.expedicao.domain.StatusPedido;
import br.com.guidara.expedicao.dto.DashboardDtos.*;
import br.com.guidara.expedicao.service.DashboardService;
import br.com.guidara.expedicao.service.ImportacaoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * POST  /api/importacoes                        upload (campo "arquivos", 1..N arquivos Excel/imagem)
 * GET   /api/importacoes                        histórico de importações
 * GET   /api/dashboard?importacaoId=            painel consolidado (sem id = última importação)
 * GET   /api/pedidos?status=&placa=&oc=         pedidos filtrados (NAO_ENVIADO, PENDENTE_FATURAMENTO, FATURADO)
 * GET   /api/pedidos/nao-enviados               atalho: grifados em amarelo
 * GET   /api/pedidos/nao-faturados              atalho: enviados sem nota
 * GET   /api/cargas                             veículos/placas com totais e status de doca
 * PATCH /api/cargas/{ocOuPlaca}/status-doca     ajuste manual: {"status":"FORA_DA_DOCA"}
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ImportacaoService importacaoService;
    private final DashboardService dashboardService;

    public ApiController(ImportacaoService importacaoService, DashboardService dashboardService) {
        this.importacaoService = importacaoService;
        this.dashboardService = dashboardService;
    }

    @PostMapping(value = "/importacoes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportacaoResumo importar(@RequestParam("arquivos") List<MultipartFile> arquivos) {
        return resumo(importacaoService.importar(arquivos));
    }

    @GetMapping("/importacoes")
    public List<ImportacaoResumo> listarImportacoes() {
        return importacaoService.listar().stream().map(ApiController::resumo).toList();
    }

    @GetMapping("/dashboard")
    public DashboardResumo dashboard(@RequestParam(required = false) UUID importacaoId) {
        return dashboardService.montar(importacaoId);
    }

    @GetMapping("/pedidos")
    public List<PedidoView> pedidos(@RequestParam(required = false) UUID importacaoId,
                                    @RequestParam(required = false) StatusPedido status,
                                    @RequestParam(required = false) String placa,
                                    @RequestParam(required = false) String oc) {
        return dashboardService.pedidos(importacaoId, status, placa, oc);
    }

    @GetMapping("/pedidos/nao-enviados")
    public List<PedidoView> naoEnviados(@RequestParam(required = false) UUID importacaoId) {
        return dashboardService.pedidos(importacaoId, StatusPedido.NAO_ENVIADO, null, null);
    }

    @GetMapping("/pedidos/nao-faturados")
    public List<PedidoView> naoFaturados(@RequestParam(required = false) UUID importacaoId) {
        return dashboardService.pedidos(importacaoId, StatusPedido.PENDENTE_FATURAMENTO, null, null);
    }

    @GetMapping("/cargas")
    public List<CargaResumo> cargas(@RequestParam(required = false) UUID importacaoId) {
        return dashboardService.cargas(importacaoId);
    }

    @PatchMapping("/cargas/{ocOuPlaca}/status-doca")
    public List<CargaResumo> alterarStatusDoca(@PathVariable String ocOuPlaca,
                                               @RequestParam(required = false) UUID importacaoId,
                                               @RequestBody AlterarStatusDocaRequest body) {
        if (body == null || body.status() == null) throw new IllegalArgumentException("Informe o status (EM_DOCA ou FORA_DA_DOCA).");
        Importacao imp = importacaoService.alterarStatusDoca(
                importacaoService.obter(importacaoId).id(), ocOuPlaca, body.status());
        return dashboardService.cargas(imp.id());
    }

    private static ImportacaoResumo resumo(Importacao i) {
        long pedidos = i.cargas().stream().mapToLong(c -> c.pedidos().size()).sum();
        return new ImportacaoResumo(i.id(), i.nomeArquivo(), i.origem().name(), i.dataHora(),
                i.cargas().size(), pedidos, i.avisos());
    }
}
