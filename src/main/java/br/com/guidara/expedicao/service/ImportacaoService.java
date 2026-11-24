package br.com.guidara.expedicao.service;

import br.com.guidara.expedicao.domain.Carga;
import br.com.guidara.expedicao.domain.Importacao;
import br.com.guidara.expedicao.domain.Importacao.OrigemImportacao;
import br.com.guidara.expedicao.domain.StatusDoca;
import br.com.guidara.expedicao.importacao.ImportadorRoteiro;
import br.com.guidara.expedicao.importacao.ImportadorRoteiro.ResultadoLeitura;
import br.com.guidara.expedicao.repository.ImportacaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ImportacaoService {

    private final List<ImportadorRoteiro> importadores;
    private final ImportacaoRepository repository;

    public ImportacaoService(List<ImportadorRoteiro> importadores, ImportacaoRepository repository) {
        this.importadores = importadores;
        this.repository = repository;
    }

    /**
     * Importa um ou mais arquivos (pode misturar Excel e imagens) como um único "dia de operação".
     * Se a mesma OC aparecer em mais de um arquivo, vale a última lida.
     */
    public Importacao importar(List<MultipartFile> arquivos) {
        if (arquivos == null || arquivos.isEmpty() || arquivos.stream().allMatch(MultipartFile::isEmpty)) {
            throw new IllegalArgumentException("Envie ao menos um arquivo (.xlsx, .xls, .png ou .jpg).");
        }

        Map<String, Carga> cargasPorChave = new LinkedHashMap<>();
        List<String> avisos = new ArrayList<>();
        Set<OrigemImportacao> origens = EnumSet.noneOf(OrigemImportacao.class);
        List<String> nomes = new ArrayList<>();
        Map<String, BigDecimal> totais = new LinkedHashMap<>();

        for (MultipartFile arquivo : arquivos) {
            if (arquivo.isEmpty()) continue;
            String nome = Objects.requireNonNullElse(arquivo.getOriginalFilename(), "arquivo");
            ImportadorRoteiro importador = importadores.stream()
                    .filter(i -> i.suporta(nome, arquivo.getContentType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Formato não suportado: " + nome + ". Use .xlsx, .xls, .png ou .jpg."));

            ResultadoLeitura r = importador.ler(bytes(arquivo), nome, arquivo.getContentType());
            origens.add(importador.origem());
            nomes.add(nome);
            avisos.addAll(r.avisos());
            r.totais().forEach((coluna, total) -> totais.merge(coluna, total, BigDecimal::add));
            long semPlaca = r.cargas().stream().filter(c -> c.placa() == null).count();
            if (semPlaca > 0) {
                avisos.add(nome + ": " + semPlaca + " carga(s) sem placa informada.");
            }

            for (Carga c : r.cargas()) {
                String chave = c.numeroOc() != null ? "OC:" + c.numeroOc()
                        : c.placa() != null ? "PLACA:" + c.placa()
                        : "SEM-ID:" + UUID.randomUUID();
                if (cargasPorChave.put(chave, c) != null) {
                    avisos.add("OC/placa " + chave.substring(chave.indexOf(':') + 1)
                            + " apareceu em mais de um arquivo; mantida a última leitura (" + nome + ").");
                }
            }
        }

        OrigemImportacao origem = origens.contains(OrigemImportacao.IMAGEM) ? OrigemImportacao.IMAGEM : OrigemImportacao.EXCEL;
        Importacao imp = new Importacao(UUID.randomUUID(), String.join(", ", nomes), origem,
                LocalDateTime.now(), new ArrayList<>(cargasPorChave.values()), avisos, totais);
        return repository.salvar(imp);
    }

    /** Ajuste manual pela operação: marca o veículo como em doca / fora da doca. */
    public Importacao alterarStatusDoca(UUID importacaoId, String numeroOc, StatusDoca status) {
        Importacao imp = obter(importacaoId);
        boolean achou = false;
        List<Carga> novas = new ArrayList<>();
        for (Carga c : imp.cargas()) {
            if (numeroOc.equals(c.numeroOc()) || numeroOc.equalsIgnoreCase(c.placa())) {
                novas.add(c.comStatusDoca(status));
                achou = true;
            } else {
                novas.add(c);
            }
        }
        if (!achou) throw new NoSuchElementException("OC/placa não encontrada: " + numeroOc);
        return repository.atualizar(new Importacao(imp.id(), imp.nomeArquivo(), imp.origem(), imp.dataHora(), novas, imp.avisos(), imp.totaisPlanilha()));
    }

    public Importacao obter(UUID id) {
        return (id == null ? repository.ultima() : repository.buscar(id))
                .orElseThrow(() -> new NoSuchElementException(
                        id == null ? "Nenhuma planilha/imagem importada ainda." : "Importação não encontrada: " + id));
    }

    public List<Importacao> listar() {
        return repository.listar();
    }

    private static byte[] bytes(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Falha ao ler o arquivo " + f.getOriginalFilename(), e);
        }
    }
}
