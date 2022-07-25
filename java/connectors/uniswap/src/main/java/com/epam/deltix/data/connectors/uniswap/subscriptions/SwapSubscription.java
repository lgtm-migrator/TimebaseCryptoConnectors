package com.epam.deltix.data.connectors.uniswap.subscriptions;

import com.epam.deltix.data.connectors.commons.GraphQlQuery;
import com.epam.deltix.data.connectors.commons.HttpPoller;
import com.epam.deltix.data.connectors.commons.Logger;
import com.epam.deltix.data.connectors.commons.MessageOutput;
import com.epam.deltix.data.connectors.commons.json.JsonObject;
import com.epam.deltix.data.connectors.uniswap.UniswapCollectionPoller;
import com.epam.deltix.data.uniswap.Swap;
import com.epam.deltix.data.uniswap.SwapAction;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class SwapSubscription extends Subscription {
    private static final GraphQlQuery.Query QUERY_TEMPLATE = GraphQlQuery.query("swaps");

    static {
        QUERY_TEMPLATE.arguments().withOrderBy("timestamp");
        QUERY_TEMPLATE.withScalar("id");

        final GraphQlQuery.Object trx = QUERY_TEMPLATE.withObject("transaction");
        trx.withScalar("id");
        trx.withScalar("blockNumber");

        QUERY_TEMPLATE.withScalar("timestamp");

        final GraphQlQuery.Object pool = QUERY_TEMPLATE.withObject("pool");
        pool.withScalar("id");

        final GraphQlQuery.Object token0 = QUERY_TEMPLATE.withObject("token0");
        token0.withScalar("id");
        token0.withScalar("symbol");

        final GraphQlQuery.Object token1 = QUERY_TEMPLATE.withObject("token1");
        token1.withScalar("id");
        token1.withScalar("symbol");

        QUERY_TEMPLATE.withScalar("sender");
        QUERY_TEMPLATE.withScalar("recipient");
        QUERY_TEMPLATE.withScalar("origin");
        QUERY_TEMPLATE.withScalar("amount0");
        QUERY_TEMPLATE.withScalar("amount1");
        QUERY_TEMPLATE.withScalar("amountUSD");
        QUERY_TEMPLATE.withScalar("sqrtPriceX96");
        QUERY_TEMPLATE.withScalar("tick");
        QUERY_TEMPLATE.withScalar("logIndex");
    }

    public SwapSubscription(
            final String uri,
            final MessageOutput messageOutput,
            final Logger logger,
            final IdentifiedUniswapSymbol... symbols) {
        super(uri, messageOutput, logger, symbols);
    }

    @Override
    public Collection<HttpPoller> get() {
        if (symbols.length == 0) {
            return List.of(new UniswapCollectionPoller<>(
                    uri,
                    QUERY_TEMPLATE,
                    Swap::new,
                    SwapAction::new,
                    messageOutput));
        }

        long now = System.currentTimeMillis() / 1000;

        return Arrays.stream(symbols).map(symbol -> {
            final GraphQlQuery.Query query = QUERY_TEMPLATE.copy();
            Predicate<JsonObject> filter = object -> true;

            if (symbol.hasToken0() && symbol.hasToken1()) {
                query.arguments().withWhere("token0: \"" + symbol.token0Id() + "\", " +
                        "token1: \"" + symbol.token1Id() + "\"," +
                        "timestamp_gte: \"" + now + "\"");
            }

            return new UniswapCollectionPoller<>(
                    uri,
                    query,
                    filter,
                    Swap::new,
                    SwapAction::new,
                    messageOutput,
                    false);
        }).collect(Collectors.toList());
    }
}
