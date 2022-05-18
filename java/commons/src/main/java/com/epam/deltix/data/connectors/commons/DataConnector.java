package com.epam.deltix.data.connectors.commons;

/**
 *
 * @param <T>
 */
public abstract class DataConnector<T extends DataConnectorSettings> implements AutoCloseable {
    private final T settings;
    private final MdModel model;
    private final Logger logger;

    protected DataConnector(final T settings, final MdModel model) {
        this.settings = settings;
        this.model = model;
        this.logger = JulLogger.forConnector(settings().getName());
    }

    private Retrier<MdFeed> retrier; // guarded by this
    private boolean closed; // guarded by this

    /**
     * @return T - settings of data connector
     */
    public T settings() {
        return settings;
    }

    /**
     *
     * @return
     */
    public MdModel model() {
        return model;
    }

    public Logger logger() {
        return logger;
    }

    /**
     *
     * @param selected
     * @param symbols
     */
    public final synchronized void subscribe(final MdModel.Options selected, final String... symbols) {
        if (closed) {
            throw new IllegalStateException("Closed");
        }

        Util.closeQuiet(retrier);

        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Empty model selection");
        }

        final SymbolMapper symbolMapper = new SymbolMapper(
                new TbMessageOutputFactory(
                        settings.getTbUrl(),
                        settings.getStream(),
                        selected.types(),
                        logger),
/*
                () -> new CloseableMessageOutput() {
                    @Override
                    public void close() {
                        System.out.println("CLOSE");
                    }

                    @Override
                    public void send(final com.epam.deltix.timebase.messages.InstrumentMessage message) {
                        System.out.println(message);
                    }
                },
*/
                symbols);

        retrier = new Retrier<>(doSubscribe(
                selected,
                symbolMapper,
                symbolMapper.normalized()),
                10_000,
                logger);

        retrier.start();
    }

    /**
     *
     */
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }

        Util.closeQuiet(retrier);
    }

    /**
     *
     * @param selected
     * @param outputFactory
     * @param symbols
     * @return
     */
    protected abstract RetriableFactory<MdFeed> doSubscribe(
            MdModel.Options selected,
            CloseableMessageOutputFactory outputFactory,
            String... symbols);
}
