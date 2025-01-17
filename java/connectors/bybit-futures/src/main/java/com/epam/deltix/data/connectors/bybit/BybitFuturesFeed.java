package com.epam.deltix.data.connectors.bybit;

import com.epam.deltix.data.connectors.commons.*;
import com.epam.deltix.data.connectors.commons.json.*;
import com.epam.deltix.timebase.messages.TypeConstants;
import com.epam.deltix.timebase.messages.universal.AggressorSide;

import java.util.Arrays;

public class BybitFuturesFeed extends MdSingleWsFeed {
    private static final long PING_PERIOD = 5000;

    // all fields are used by one single thread of WsFeed's ExecutorService
    private final JsonValueParser jsonParser = new JsonValueParser();
    private final Iso8601DateTimeParser dtParser = new Iso8601DateTimeParser();

    private final int depth;

    private enum UpdateType {
        delete,
        update,
        insert
    }

    public BybitFuturesFeed(
            final String uri,
            final int depth,
            final MdModel.Options selected,
            final CloseableMessageOutput output,
            final ErrorListener errorListener,
            final Logger logger,
            final String... symbols) {

        super("BYBIT",
                uri,
                depth,
                30000,
                selected,
                output,
                errorListener,
                logger,
                getPeriodicalJsonTask(),
                symbols);

        this.depth = depth;
    }

    private static PeriodicalJsonTask getPeriodicalJsonTask() {
        return new PeriodicalJsonTask() {
            @Override
            public long delayMillis() {
                return PING_PERIOD;
            }

            @Override
            public void execute(JsonWriter jsonWriter) {
                jsonWriter.startObject();
                jsonWriter.objectMember("op");
                jsonWriter.stringValue("ping");
                jsonWriter.endObject();
                jsonWriter.eoj();
            }
        };
    }

    @Override
    protected void subscribe(JsonWriter jsonWriter, String... symbols) {
        JsonValue subscriptionJson = JsonValue.newObject();
        JsonObject body = subscriptionJson.asObject();

        body.putString("op", "subscribe");
        JsonArray args = body.putArray("args");
        Arrays.asList(symbols).forEach(s -> {
            if (selected().level1() || selected().level2()) {
                args.addString(
                    String.format(
                        depth <= 25 ? "orderBookL2_25.%s" : "orderBook_200.100ms.%s",
                        s)
                );
            }
            if (selected().trades()) {
                args.addString(String.format("trade.%s", s));
            }
        });

        subscriptionJson.toJsonAndEoj(jsonWriter);
    }

    @Override
    protected void onJson(final CharSequence data, final boolean last, final JsonWriter jsonWriter) {
        jsonParser.parse(data);

        if (!last) {
            return;
        }

        JsonValue jsonValue = jsonParser.eoj();
        JsonObject object = jsonValue.asObject();

        String topic = object.getString("topic");
        if (topic == null) {
            return;
        }

        String[] topicElements = topic.split("\\.");
        if (topicElements.length != 2 && topicElements.length != 3) {
            return;
        }

        String instrument = topicElements[topicElements.length - 1];
        if (topic.startsWith("orderBook")) {
            String type = object.getString("type");
            if ("snapshot".equalsIgnoreCase(type)) {
                long timestamp = getTimestamp(object,"timestamp_e6") / 1000;
                JsonObject dataObject = object.getObject("data");
                JsonArray dataArray = object.getArray("data");
                if (dataObject != null) {
                    QuoteSequenceProcessor quotesListener = processor().onBookSnapshot(instrument, timestamp);
                    processSnapshot(quotesListener, dataObject.getArray("order_book"));
                    quotesListener.onFinish();
                } else if (dataArray != null) {
                    QuoteSequenceProcessor quotesListener = processor().onBookSnapshot(instrument, timestamp);
                    processSnapshot(quotesListener, dataArray);
                    quotesListener.onFinish();
                }
            } else if ("delta".equalsIgnoreCase(type)) {
                long timestamp = getTimestamp(object,"timestamp_e6") / 1000;
                JsonObject dataObject = object.getObject("data");
                if (dataObject == null) {
                    return;
                }

                QuoteSequenceProcessor quotesListener = processor().onBookUpdate(instrument, timestamp);
                processChanges(quotesListener, dataObject.getArray("delete"), UpdateType.delete);
                processChanges(quotesListener, dataObject.getArray("update"), UpdateType.update);
                processChanges(quotesListener, dataObject.getArray("insert"), UpdateType.insert);
                quotesListener.onFinish();
            }
        } else if (topic.startsWith("trade.")) {
            JsonArray trades = object.getArray("data");
            if (trades != null) {
                for (int i = 0; i < trades.size(); ++i) {
                    JsonObject trade = trades.getObject(i);

                    long price = trade.getDecimal64("price");
                    long size = trade.getDecimal64("size");
                    long timestamp = dtParser.set(trade.getStringRequired("timestamp")).millis();
                    String tradeSide = trade.getString("side");

                    AggressorSide side = "buy".equalsIgnoreCase(tradeSide) ? AggressorSide.BUY : AggressorSide.SELL;

                    processor().onTrade(instrument, timestamp, price, size, side);
                }
            }
        }
    }

    private void processSnapshot(QuoteSequenceProcessor quotesListener, JsonArray quotes) {
        if (quotes == null) {
            return;
        }

        for (int i = 0; i < quotes.size(); i++) {
            JsonObject quote = quotes.getObjectRequired(i);
            quotesListener.onQuote(
                quote.getDecimal64("price"),
                quote.getDecimal64("size"),
                "sell".equalsIgnoreCase(quote.getStringRequired("side"))
            );
        }
    }

    private void processChanges(QuoteSequenceProcessor quotesListener, JsonArray changes, UpdateType updateType) {
        if (changes == null) {
            return;
        }

        for (int i = 0; i < changes.size(); i++) {
            final JsonObject change = changes.getObjectRequired(i);

            long size = TypeConstants.DECIMAL_NULL;;
            if (updateType != UpdateType.delete) {
                size = change.getDecimal64("size");
            }

            quotesListener.onQuote(
                change.getDecimal64("price"),
                size,
                "sell".equalsIgnoreCase(change.getStringRequired("side"))
            );
        }
    }

    private long getTimestamp(JsonObject object, String fieldName) {
        String stringValue = object.getString(fieldName);
        if (stringValue != null) {
            return Long.parseLong(stringValue);
        } else {
            return object.getLong(fieldName);
        }
    }
}
