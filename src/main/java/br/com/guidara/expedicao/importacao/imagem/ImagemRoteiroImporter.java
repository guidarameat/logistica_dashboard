package br.com.guidara.expedicao.importacao.imagem;

import br.com.guidara.expedicao.config.ImportacaoProperties;
import br.com.guidara.expedicao.domain.Carga;
import br.com.guidara.expedicao.domain.Importacao.OrigemImportacao;
import br.com.guidara.expedicao.domain.Pedido;
import br.com.guidara.expedicao.importacao.ImportadorRoteiro;
import br.com.guidara.expedicao.importacao.TextoUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lê prints/fotos do "Relatório Roteiro Entrega" usando a API da OpenAI (visão + JSON Schema "strict").
 *
 * Por que LLM multimodal e não Tesseract + OpenCV:
 *  - o Tesseract devolve texto solto; reconstruir a tabela e associar cada linha à cor de fundo
 *    exige muito código frágil;
 *  - o modelo lê a tabela inteira e marca o destaque amarelo linha a linha, devolvendo JSON
 *    garantidamente no formato de {@link RoteiroExtraido}.
 *
 * A chave vem de OPENAI_API_KEY (arquivo .env ou variável de ambiente).
 */
@Component
public class ImagemRoteiroImporter implements ImportadorRoteiro {

    private static final String URL = "https://api.openai.com/v1/chat/completions";

    private static final String INSTRUCOES = """
            Você recebe a imagem de um ou mais "Relatório Roteiro Entrega" de uma indústria de alimentos.
            Cada relatório tem um cabeçalho cinza com "Nº OC", "Veículo" (placa), "Motorista", "Data Embarque"
            e "Rota", seguido de uma tabela com as colunas:
            Fil | Pedido | Nota | Cliente | CEP | Endereço | Nr | Bairro | Cidade | Peso Líquido | Volumes | Observações.

            Transcreva TODAS as linhas da tabela de pedidos, sem pular nenhuma, copiando os valores exatamente
            como aparecem (não corrija nomes, não complete endereços cortados). Ignore os quadros
            "Resumo por Cidade" e "Volume por Caixas" e as linhas de totais.

            Para cada linha, informe destacadoAmarelo = true quando a linha (ou a maior parte dela) estiver
            grifada com fundo amarelo / laranja-claro. Linhas com fundo branco ou cinza claro são false.
            Analise a cor de cada linha individualmente: linhas amarelas podem ser consecutivas.

            Pesos e volumes: converta a vírgula decimal para número (257,10 -> 257.10).
            Em pesoTotalBruto e pesoTotalLiquido informe, respectivamente, os valores de "Peso Total Bruto"
            e "Peso Total Líquido" do rodapé de cada relatório.
            Campos vazios no relatório: use string vazia.
            Se algo estiver ilegível ou cortado, preencha o melhor possível e descreva o problema em
            observacoesLeitura.
            """;

    private final ImportacaoProperties.Visao cfg;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final RestClient http;

    public ImagemRoteiroImporter(ImportacaoProperties props,
                                 @Value("${OPENAI_API_KEY:}") String apiKey,
                                 ObjectMapper mapper,
                                 RestClient.Builder restClientBuilder) {
        this.cfg = props.visao();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.mapper = mapper;
        this.http = restClientBuilder.build();
    }

    @Override
    public boolean suporta(String nomeArquivo, String contentType) {
        return mimeType(nomeArquivo, contentType) != null;
    }

    @Override
    public OrigemImportacao origem() {
        return OrigemImportacao.IMAGEM;
    }

    @Override
    public ResultadoLeitura ler(byte[] conteudo, String nomeArquivo, String contentType) {
        if (!cfg.habilitada()) {
            throw new IllegalStateException("Leitura de imagens desabilitada (guidara.visao.habilitada=false).");
        }
        if (apiKey.isEmpty() || apiKey.startsWith("cole-")) {
            throw new IllegalStateException(
                    "Chave da OpenAI não configurada. Preencha OPENAI_API_KEY no arquivo .env e reinicie o sistema.");
        }

        String dataUrl = "data:" + mimeType(nomeArquivo, contentType) + ";base64,"
                + Base64.getEncoder().encodeToString(conteudo);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.modelo());
        body.put("max_completion_tokens", cfg.maxTokens());
        body.put("messages", List.of(
                Map.of("role", "system", "content", INSTRUCOES),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl, "detail", "high")),
                        Map.of("type", "text", "text", "Extraia os dados deste relatório.")))));
        body.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of("name", "roteiro_extraido", "strict", true, "schema", SCHEMA)));

        JsonNode resposta = http.post()
                .uri(URL)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode msg = resposta == null ? null : resposta.path("choices").path(0).path("message");
        if (msg == null || msg.isMissingNode()) {
            throw new IllegalStateException("Resposta vazia da OpenAI para a imagem " + nomeArquivo + ".");
        }
        if (msg.hasNonNull("refusal")) {
            throw new IllegalStateException("A OpenAI recusou ler a imagem " + nomeArquivo + ": " + msg.get("refusal").asText());
        }
        String finish = resposta.path("choices").path(0).path("finish_reason").asText();
        if ("length".equals(finish)) {
            throw new IllegalStateException("A resposta da OpenAI foi cortada (limite de tokens). Aumente guidara.visao.max-tokens.");
        }

        try {
            RoteiroExtraido extraido = mapper.readValue(msg.path("content").asText(), RoteiroExtraido.class);
            return converter(extraido, nomeArquivo);
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível interpretar a resposta da OpenAI: " + e.getMessage(), e);
        }
    }

    private ResultadoLeitura converter(RoteiroExtraido extraido, String nomeArquivo) {
        List<String> avisos = new ArrayList<>();
        if (extraido.observacoesLeitura() != null) {
            extraido.observacoesLeitura().forEach(o -> avisos.add(nomeArquivo + ": " + o));
        }
        List<Carga> cargas = new ArrayList<>();
        if (extraido.cargas() == null) return new ResultadoLeitura(cargas, avisos);

        for (RoteiroExtraido.CargaExtraida c : extraido.cargas()) {
            List<Pedido> pedidos = c.pedidos() == null ? List.of() : c.pedidos().stream()
                    .map(p -> new Pedido(
                            p.filial(), p.pedido(), p.nota(), p.cliente(), p.cep(), p.endereco(), p.numero(),
                            p.bairro(), p.cidade(), decimal(p.pesoLiquido()), decimal(p.volumes()),
                            vazioParaNull(p.observacoes()), null, null, p.destacadoAmarelo()))
                    .toList();
            cargas.add(new Carga(
                    c.numeroOc(),
                    TextoUtils.extrairPlaca(c.placa()).orElse(c.placa()),
                    vazioParaNull(c.motorista()),
                    TextoUtils.extrairData(c.dataEmbarque()).orElse(null),
                    vazioParaNull(c.rota()),
                    pedidos,
                    Carga.pesoBrutoConsiderado(decimal(c.pesoTotalBruto()), decimal(c.pesoTotalLiquido()), pedidos),
                    null));
        }
        avisos.add(nomeArquivo + ": dados lidos por IA a partir de imagem. Confira os destaques amarelos antes de usar o relatório.");
        return new ResultadoLeitura(cargas, avisos);
    }

    // ------------------------------------------------------------------
    // JSON Schema (modo strict da OpenAI: todos os campos obrigatórios e additionalProperties=false)

    private static final Map<String, Object> SCHEMA = objeto(Map.of(
            "cargas", Map.of("type", "array", "items", objeto(linked(
                    "numeroOc", texto("Número da OC (ex.: 8261)"),
                    "placa", texto("Placa do veículo exatamente como impressa (ex.: PUV-0E30)"),
                    "motorista", texto("Nome do motorista; vazio se não informado"),
                    "dataEmbarque", texto("Data de embarque no formato dd/MM/yyyy"),
                    "rota", texto("Texto da rota; vazio se não informado"),
                    "pesoTotalBruto", Map.of("type", "number",
                            "description", "Valor de 'Peso Total Bruto' no rodapé do relatório (357,196 -> 357.196)"),
                    "pesoTotalLiquido", Map.of("type", "number",
                            "description", "Valor de 'Peso Total Líquido' no rodapé do relatório (364,407 -> 364.407)"),
                    "pedidos", Map.of("type", "array", "items", objeto(linked(
                            "filial", texto("Coluna Fil"),
                            "pedido", texto("Coluna Pedido"),
                            "nota", texto("Coluna Nota exatamente como impressa; '0' quando não faturado"),
                            "cliente", texto("Coluna Cliente completa, incluindo o código"),
                            "cep", texto("Coluna CEP"),
                            "endereco", texto("Coluna Endereço"),
                            "numero", texto("Coluna Nr"),
                            "bairro", texto("Coluna Bairro"),
                            "cidade", texto("Coluna Cidade"),
                            "pesoLiquido", Map.of("type", "number", "description", "Peso líquido (257,10 -> 257.10)"),
                            "volumes", Map.of("type", "number"),
                            "observacoes", texto("Coluna Observações"),
                            "destacadoAmarelo", Map.of("type", "boolean",
                                    "description", "true se a linha estiver grifada com fundo amarelo/laranja-claro"))))))),
            "observacoesLeitura", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "Trechos ilegíveis ou duvidosos; lista vazia se tudo foi lido com clareza")));

    private static Map<String, Object> objeto(Map<String, Object> props) {
        return Map.of("type", "object", "properties", props,
                "required", List.copyOf(props.keySet()), "additionalProperties", false);
    }

    private static Map<String, Object> texto(String descricao) {
        return Map.of("type", "string", "description", descricao);
    }

    /** Map com ordem preservada (Map.of só aceita até 10 pares). */
    private static Map<String, Object> linked(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String mimeType(String nome, String contentType) {
        String n = (nome == null ? "" : nome).toLowerCase(Locale.ROOT);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png") || ct.equals("image/png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || ct.equals("image/jpeg")) return "image/jpeg";
        if (n.endsWith(".webp") || ct.equals("image/webp")) return "image/webp";
        return null;
    }

    private static BigDecimal decimal(Double d) {
        return d == null ? null : BigDecimal.valueOf(d);
    }

    private static String vazioParaNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
