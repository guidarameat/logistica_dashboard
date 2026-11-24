package br.com.guidara.expedicao.importacao;

import br.com.guidara.expedicao.domain.Carga;
import br.com.guidara.expedicao.domain.Pedido;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Acumulador mutável usado durante a leitura; gera a Carga imutável no final. */
public class CargaBuilder {
    public String numeroOc;
    public String placa;
    public String motorista;
    public LocalDate dataEmbarque;
    public String rota;
    /** "Peso Total Bruto" do rodapé (ou soma da coluna Peso Bruto numa tabela plana). */
    public BigDecimal pesoBrutoTotal;
    /** "Peso Total Líquido" do rodapé. */
    public BigDecimal pesoLiquidoTotal;
    public final List<Pedido> pedidos = new ArrayList<>();

    public boolean vazio() {
        return pedidos.isEmpty() && numeroOc == null && placa == null;
    }

    /** Soma (usado quando o peso bruto vem por linha, numa tabela plana). */
    public void somarPesoBruto(BigDecimal peso) {
        if (peso == null) return;
        pesoBrutoTotal = pesoBrutoTotal == null ? peso : pesoBrutoTotal.add(peso);
    }

    public Carga build() {
        return new Carga(numeroOc, placa, motorista, dataEmbarque, rota, pedidos,
                Carga.pesoBrutoConsiderado(pesoBrutoTotal, pesoLiquidoTotal, pedidos), null);
    }
}
