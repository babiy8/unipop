package org.unipop.jdbc.controller.simple;

import com.google.common.collect.Maps;
import org.apache.commons.collections4.keyvalue.DefaultMapEntry;
import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.MutableMetrics;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalMetrics;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.util.iterator.EmptyIterator;
import org.jooq.*;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unipop.common.util.PredicatesTranslator;
import org.unipop.jdbc.schemas.RowEdgeSchema;
import org.unipop.jdbc.schemas.RowVertexSchema;
import org.unipop.jdbc.schemas.jdbc.JdbcEdgeSchema;
import org.unipop.jdbc.schemas.jdbc.JdbcSchema;
import org.unipop.jdbc.schemas.jdbc.JdbcVertexSchema;
import org.unipop.jdbc.utils.TimingExecuterListener;
import org.unipop.query.aggregation.LocalQuery;
import org.unipop.query.controller.SimpleController;
import org.unipop.query.mutation.AddEdgeQuery;
import org.unipop.query.mutation.AddVertexQuery;
import org.unipop.query.mutation.PropertyQuery;
import org.unipop.query.mutation.RemoveQuery;
import org.unipop.query.predicates.PredicatesHolder;
import org.unipop.query.search.DeferredVertexQuery;
import org.unipop.query.search.SearchQuery;
import org.unipop.query.search.SearchVertexQuery;
import org.unipop.schema.element.ElementSchema;
import org.unipop.schema.reference.DeferredVertex;
import org.unipop.structure.UniEdge;
import org.unipop.structure.UniElement;
import org.unipop.structure.UniGraph;
import org.unipop.structure.UniVertex;
import org.unipop.util.MetricsRunner;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.groupingSets;
import static org.jooq.impl.DSL.table;

/**
 * @author Gur Ronen
 * @since 6/12/2016
 */
public class RowController implements SimpleController {
    private final static Logger logger = LoggerFactory.getLogger(RowController.class);

    private final DSLContext dslContext;
    private final UniGraph graph;

    private Set<? extends RowVertexSchema> vertexSchemas;
    private Set<? extends RowEdgeSchema> edgeSchemas;

    private final PredicatesTranslator<Condition> predicatesTranslator;

    public <E extends Element> RowController(UniGraph graph, DSLContext context, Set<JdbcSchema> schemaSet, PredicatesTranslator<Condition> predicatesTranslator) {
        this.graph = graph;
        this.dslContext = context;

        extractRowSchemas(schemaSet);
        this.predicatesTranslator = predicatesTranslator;
    }

    @Override
    public <E extends Element> Iterator<E> search(SearchQuery<E> uniQuery) {
        SelectCollector<JdbcSchema<E>, Select, E> collector = new SelectCollector<>(
                schema -> schema.getSearch(uniQuery,
                        schema.toPredicates(uniQuery.getPredicates()),
                        dslContext),
                (schema, results) -> schema.parseResults(results, uniQuery)
        );
        Set<? extends JdbcSchema<E>> schemas = this.getSchemas(uniQuery.getReturnType());

        Map<JdbcSchema<E>, Select> selects = schemas.stream().collect(collector);

//        Function<JdbcSchema<E>, PredicatesHolder> toPredicatesFunction = (schema) -> schema.toPredicates(uniQuery.getPredicates());

        return this.search(uniQuery, selects, collector);
    }

    @Override
    public void fetchProperties(DeferredVertexQuery uniQuery) {
        SelectCollector<JdbcSchema<Vertex>, Select, Vertex> collector = new SelectCollector<>(
                schema -> schema.getSearch(uniQuery,
                        schema.toPredicates(uniQuery.getPredicates()),
                        dslContext),
                (schema, results) -> schema.parseResults(results, uniQuery)
        );

//        Function<JdbcVertexSchema, PredicatesHolder> toPredicatesFunction = (schema) ->
//                schema.toPredicates(uniQuery.getVertices());
        Map<JdbcSchema<Vertex>, Select> selects = vertexSchemas.stream().collect(collector);
        Iterator<Vertex> searchIterator = this.search(uniQuery, selects, collector);

        Map<Object, DeferredVertex> vertexMap =
                uniQuery.getVertices().stream().collect(Collectors.toMap(UniElement::id, Function.identity(), (a, b) -> a));
        searchIterator.forEachRemaining(newVertex -> {
            DeferredVertex deferredVertex = vertexMap.get(newVertex.id());
            if (deferredVertex != null) {
                deferredVertex.loadProperties(newVertex);
            }
        });
    }

    @Override
    public Iterator<Edge> search(SearchVertexQuery uniQuery) {

        SelectCollector<JdbcSchema<Edge>, Select, Edge> collector = new SelectCollector<>(
                schema -> schema.getSearch(uniQuery,
                        schema.toPredicates(uniQuery.getPredicates()),
                        dslContext),
                (schema, results) -> schema.parseResults(results, uniQuery)
        );

        Map<JdbcSchema<Edge>, Select> selects = edgeSchemas.stream().collect(collector);

//        Function<JdbcEdgeSchema, PredicatesHolder> toPredicatesFunction = (schema) -> schema.toPredicates(
//                uniQuery.getVertices(), uniQuery.getDirection(), uniQuery.getPredicates());
        return this.search(uniQuery, selects, collector);
    }

//    @Override
//    public <S extends Element> Iterator<org.javatuples.Pair<String, S>> local(LocalQuery<S> query) {
//
//        return null;
//    }

    @Override
    public Edge addEdge(AddEdgeQuery uniQuery) {
        UniEdge edge = new UniEdge(uniQuery.getProperties(), uniQuery.getOutVertex(), uniQuery.getInVertex(), this.graph);
        try {
            if (this.insert(edgeSchemas, edge)) return edge;
        } catch (DataAccessException ex) {
            throw Graph.Exceptions.edgeWithIdAlreadyExists(edge.id());
        }

        return null;
    }

    @Override
    public Vertex addVertex(AddVertexQuery uniQuery) {
        UniVertex vertex = new UniVertex(uniQuery.getProperties(), this.graph);
        try {
            if (this.insert(this.vertexSchemas, vertex)) {
                return vertex;
            }
        } catch (DataAccessException ex) {
            throw Graph.Exceptions.vertexWithIdAlreadyExists(vertex.id());
        }

        return vertex;
    }

    @Override
    public <E extends Element> void property(PropertyQuery<E> uniQuery) {
        Set<? extends JdbcSchema<E>> schemas = this.getSchemas(uniQuery.getElement().getClass());
        this.update(schemas, uniQuery.getElement());
    }

    @Override
    public <E extends Element> void remove(RemoveQuery<E> uniQuery) {
        uniQuery.getElements().forEach(el -> {
            Set<? extends JdbcSchema<E>> schemas = this.getSchemas(el.getClass());

            for (JdbcSchema<E> schema : schemas) {

                DeleteWhereStep deleteStep = this.getDslContext().delete(table(schema.getTable()));

                Condition conditions = this.translateElementsToConditions(schema, Collections.singletonList(el));
                Delete step = deleteStep.where(conditions);

                step.execute();
                logger.debug("removed element. element: {}, schema: {}, command: {}", el, schema, step);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <E extends Element> Set<? extends JdbcSchema<E>> getSchemas(Class elementClass) {
        if (Vertex.class.isAssignableFrom(elementClass)) {
            return vertexSchemas.stream().map(v -> (JdbcSchema<E>) v).collect(Collectors.toSet());
        } else {
            return edgeSchemas.stream().map(e -> (JdbcSchema<E>) e).collect(Collectors.toSet());
        }
    }

    private <E extends Element, S extends JdbcSchema<E>> void fillChildren(List<MutableMetrics> children, Map<S, Select> schemas) {
        List<org.javatuples.Pair<Long, Integer>> timing = TimingExecuterListener.timing.values().stream().collect(Collectors.toList());
        List<Map.Entry<S, Select>> sqls = schemas.entrySet().stream().collect(Collectors.toList());
        for (int i = 0; i < sqls.size(); i++) {
            org.javatuples.Pair<Long, Integer> timingCount = timing.get(i);
            if (i < children.size()) {
                MutableMetrics child = children.get(i);
                if (timingCount != null) {
                    child.setCount(TraversalMetrics.ELEMENT_COUNT_ID, timingCount.getValue1());
                    child.setDuration(timingCount.getValue0(), TimeUnit.NANOSECONDS);
                }
            }
            timing.remove(sqls.get(i).getValue().getSQL());
        }
    }

    @SuppressWarnings("unchecked")
    private <E extends Element, S extends JdbcSchema<E>> Iterator<E> search(SearchQuery<E> query, Map<JdbcSchema<E>, Select> selects, SelectCollector<JdbcSchema<E>, Select, E> collector) {
//        Map<S, Select> schemas = allSchemas.stream()
//                .map(schema -> Pair.of(schema, toSearchFunction.apply(schema)))
//                .filter(pair -> pair.getRight() != null)
//                .map(pair -> Pair.of(pair.getLeft(), pair.getLeft().getSearch(query, pair.getRight(), this.getDslContext())))
//                .filter(pair -> pair.getRight() != null)
//                .collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

        MetricsRunner metrics = new MetricsRunner(this, query,
                selects.keySet().stream().map(s -> ((ElementSchema) s)).collect(Collectors.toList()));

        logger.info("mapped schemas for search, schemas: {}", selects);
        if (selects.size() == 0) return EmptyIterator.instance();

        Iterator<JdbcSchema<E>> schemaIterator = selects.keySet().iterator();
        Set<E> collect = selects.values().stream()
                .map(Select::fetch)
                .flatMap(res -> collector.parse.apply(schemaIterator.next(),res).stream())
                .distinct().collect(Collectors.toSet());


        metrics.stop((children) -> fillChildren(children, selects));

        logger.info("results: {}", collect);
        return collect.iterator();
    }

    private <E extends Element> boolean insert(Set<? extends JdbcSchema<E>> schemas, E element) {
        for (JdbcSchema<E> schema : schemas) {
            Query query = schema.getInsertStatement(element);
            if (query == null) continue;
            int changeSetCount = dslContext.execute(query);
            if (logger.isDebugEnabled())
                logger.debug("executed insertion, query: {}", dslContext.render(query));
            if (changeSetCount == 0) {
                logger.error("no rows changed on insertion. query: {}, element: {}", dslContext.render(query), element);
            }
            return true;
        }
        return false;
    }

    private <E extends Element> void update(Set<? extends JdbcSchema<E>> schemas, E element) {
        for (JdbcSchema<E> schema : schemas) {
            JdbcSchema.Row row = schema.toRow(element);

            if (row == null) continue;

            Map<Field<?>, Object> fieldMap = Maps.newHashMap();
            row.getFields().entrySet().stream().map(this::mapSet).forEach(en -> fieldMap.put(en.getKey(), en.getValue()));

            Update step = this.getDslContext().update(table(schema.getTable()))
                    .set(fieldMap).where(field(schema.getFieldByPropertyKey(T.id.getAccessor())).eq(row.getId()));

            step.execute();
            logger.info("executed update statement with following parameters, step: {}, element: {}, schema: {}", step, element, schema);
            dslContext.execute("commit;");
        }
    }

    private Map.Entry<Field<?>, Object> mapSet(Map.Entry<String, Object> entry) {
        return new DefaultMapEntry<>(field(entry.getKey()), entry.getValue());
    }

    private <E extends Element> Condition translateElementsToConditions(JdbcSchema<E> schema, List<E> elements) {
        return this.predicatesTranslator.translate(
                new PredicatesHolder(
                        PredicatesHolder.Clause.Or,
                        elements.stream()
                                .map(schema::toFields)
                                .map(Map::entrySet)
                                .flatMap(m -> m.stream().map(es -> new HasContainer(es.getKey(), P.within(es.getValue()))))
                                .collect(Collectors.toList()), Collections.emptyList()));

    }

    private <E extends Element> void extractRowSchemas(Set<JdbcSchema> schemas) {
        logger.debug("extracting row schemas to element schemas, jdbcSchemas: {}", schemas);
        Set<JdbcSchema<E>> JdbcSchemas = collectSchemas(schemas);
        this.vertexSchemas = JdbcSchemas.stream().filter(schema -> schema instanceof RowVertexSchema)
                .map(schema -> ((RowVertexSchema) schema)).collect(Collectors.toSet());
        this.edgeSchemas = JdbcSchemas.stream().filter(schema -> schema instanceof RowEdgeSchema)
                .map(schema -> ((RowEdgeSchema) schema)).collect(Collectors.toSet());
        logger.info("extraced row schemas, vertexSchemas: {}, edgeSchemas: {}", this.vertexSchemas, this.edgeSchemas);
    }

    private <E extends Element> Set<JdbcSchema<E>> collectSchemas(Set<? extends ElementSchema> schemas) {
        Set<JdbcSchema<E>> rowSchemas = new HashSet<>();

        schemas.forEach(schema -> {
            if (schema instanceof JdbcSchema) {
                rowSchemas.add((JdbcSchema<E>) schema);
                Set<JdbcSchema<E>> childSchemas = collectSchemas(schema.getChildSchemas());
                rowSchemas.addAll(childSchemas);
            }
        });
        return rowSchemas;
    }

    public DSLContext getDslContext() {
        return this.dslContext;
    }

    @Override
    public String toString() {
        return "RowController{" +
                "dslContext=" + dslContext +
                ", graph=" + graph +
                ", vertexSchemas=" + vertexSchemas +
                ", edgeSchemas=" + edgeSchemas +
                ", predicatesTranslator=" + predicatesTranslator +
                '}';
    }

    public class SelectCollector<K, V, R> implements Collector<K, Map<K, V>, Map<K, V>> {

        private final Function<? super K, ? extends V> valueMapper;

        private final BiFunction<? super K, Result, ? extends Collection<R>> parse;

        private SelectCollector(Function<? super K, ? extends V> valueMapper, BiFunction<? super K, Result, Collection<R>> parse) {
            this.valueMapper = valueMapper;
            this.parse = parse;
        }

        @Override
        public Supplier<Map<K, V>> supplier() {
            return HashMap<K, V>::new;
        }

        @Override
        public BiConsumer<Map<K, V>, K> accumulator() {
            return (map, t) -> {
                V value = valueMapper.apply(t);
                if (value != null) map.put(t, value);
            };
        }

        @Override
        public BinaryOperator<Map<K, V>> combiner() {
            return (map1, map2) -> {
                map1.putAll(map2);
                return map1;
            };
        }

        @Override
        public Function finisher() {
            return m -> m;
        }

        @Override
        public Set<Characteristics> characteristics() {
            return EnumSet.of(Collector.Characteristics.IDENTITY_FINISH);
        }
    }
}
