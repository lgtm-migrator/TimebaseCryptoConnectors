package com.epam.deltix.data.connectors.commons;

import com.epam.deltix.data.connectors.commons.l2.*;
import com.epam.deltix.dfp.Decimal;
import com.epam.deltix.qsrv.hf.pub.ExchangeCodec;

import java.util.HashMap;
import java.util.Map;

public class MarketDataProcessorImpl implements MarketDataProcessor {

    private final MessageOutput output;
    private final MdModel.Options selected;
    private final long source;
    private final int bookSize;

    private final Map<String, L2BookProcessorImpl> l2Processors = new HashMap<>();
    private final TradeProducer tradeProducer;

    public static MarketDataProcessor create(String exchangeId,
                                             MessageOutput output,
                                             MdModel.Options selected,
                                             int bookSize) {

        return new MarketDataProcessorImpl(exchangeId, output, selected, bookSize);
    }

    private MarketDataProcessorImpl(String exchangeId, MessageOutput output, MdModel.Options selected,  int bookSize) {
        this.output = output;
        this.selected = selected;
        this.source = ExchangeCodec.codeToLong(exchangeId);
        this.bookSize = bookSize;
        this.tradeProducer = new TradeProducer(source, output);
    }

    @Override
    public L2BookProcessor onBookSnapshot(String instrument, long timestamp) {
        L2BookProcessorImpl l2BookProcessor = getOrderBookProcessor(instrument);
        l2BookProcessor.packageStarted(true, timestamp);
        return l2BookProcessor;
    }

    @Override
    public L2BookProcessor onBookUpdate(String instrument, long timestamp) {
        L2BookProcessorImpl l2BookProcessor = getOrderBookProcessor(instrument);
        l2BookProcessor.packageStarted(false, timestamp);
        return l2BookProcessor;
    }

    @Override
    public void onTrade(String instrument, long timestamp, @Decimal long price, @Decimal long size) {
        tradeProducer.onTrade(timestamp, instrument, price, size);
    }

    private L2BookProcessorImpl getOrderBookProcessor(String instrument) {
        L2BookProcessorImpl l2Processor = l2Processors.get(instrument);
        if (l2Processor == null) {
            ChainedL2Listener.Builder<DefaultItem<DefaultEvent>, DefaultEvent> builder =
                ChainedL2Listener.builder();

            if (selected.level1()) {
                builder.with(new BestBidOfferProducer<>(output));
            }
            if (selected.level2()) {
                builder.with(new L2Producer<>(output));
            }

            l2Processor = new L2BookProcessorImpl(
                L2Processor.builder()
                    .withInstrument(instrument)
                    .withSource(source)
                    .withBookOutputSize(bookSize)
                    .buildWithPriceBook(
                        builder.build()
                    )
            );

            l2Processors.put(instrument, l2Processor);
        }

        return l2Processor;
    }

}
