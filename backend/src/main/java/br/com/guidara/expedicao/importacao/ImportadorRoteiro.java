package br.com.guidara.expedicao.importacao;

import br.com.guidara.expedicao.domain.Carga;
import br.com.guidara.expedicao.domain.Importacao.OrigemImportacao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Estratégia de leitura (Excel, imagem...). Escolhida pelo ImportacaoService conforme o arquivo. */
public interface ImportadorRoteiro {

    boolean suporta(String nomeArquivo, String contentType);

    OrigemImportacao origem();

    ResultadoLeitura ler(byte[] conteudo, String nomeArquivo, String contentType);

    /**
     * @param totais soma de cada coluna numérica da planilha (título da coluna -> total), na ordem das colunas
     */
    record ResultadoLeitura(List<Carga> cargas, List<String> avisos, Map<String, BigDecimal> totais) {
        public ResultadoLeitura(List<Carga> cargas, List<String> avisos) {
            this(cargas, avisos, Map.of());
        }
    }
}
