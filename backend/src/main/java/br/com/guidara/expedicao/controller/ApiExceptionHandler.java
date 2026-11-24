package br.com.guidara.expedicao.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail unprocessable(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo maior que o limite configurado.");
    }

    /** Erro devolvido pela API da OpenAI (chave inválida, sem crédito, limite de uso...). */
    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail visao(RestClientResponseException e) {
        log.error("Erro na leitura de imagem via OpenAI: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
        String detalhe = switch (e.getStatusCode().value()) {
            case 401 -> "Chave da OpenAI inválida. Confira OPENAI_API_KEY no .env.";
            case 429 -> "Limite de uso ou crédito da OpenAI esgotado. Verifique o billing da conta.";
            default -> "A OpenAI devolveu erro " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString();
        };
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, detalhe);
    }

    /** Falha de rede ao chamar a OpenAI. */
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail rede(ResourceAccessException e) {
        log.error("Falha de conexão com a OpenAI", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Sem conexão com a OpenAI: " + e.getMessage());
    }
}
