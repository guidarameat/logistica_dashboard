package br.com.guidara.expedicao.repository;

import br.com.guidara.expedicao.domain.Importacao;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Armazenamento em memória (suficiente para o painel do dia).
 * Para histórico, troque por JPA (Importacao -> Carga -> Pedido) mantendo a mesma interface.
 */
@Repository
public class ImportacaoRepository {

    private final Map<UUID, Importacao> porId = new ConcurrentHashMap<>();
    private final AtomicReference<UUID> ultima = new AtomicReference<>();

    public Importacao salvar(Importacao imp) {
        porId.put(imp.id(), imp);
        ultima.set(imp.id());
        return imp;
    }

    /** Substitui uma importação já existente (ex.: após alterar o status de doca) sem mudar a "última". */
    public Importacao atualizar(Importacao imp) {
        porId.put(imp.id(), imp);
        return imp;
    }

    public Optional<Importacao> buscar(UUID id) {
        return Optional.ofNullable(porId.get(id));
    }

    public Optional<Importacao> ultima() {
        UUID id = ultima.get();
        return id == null ? Optional.empty() : buscar(id);
    }

    public List<Importacao> listar() {
        return porId.values().stream()
                .sorted(Comparator.comparing(Importacao::dataHora).reversed())
                .toList();
    }
}
