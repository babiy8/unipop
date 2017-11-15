package org.unipop.structure;

import org.apache.tinkerpop.gremlin.structure.Property;
import org.unipop.query.mutation.PropertyQuery;

import java.util.*;

public class UniVertexMetaProperty<V> extends UniVertexProperty<V> {

    private Map<String, Property> properties;

    public UniVertexMetaProperty(UniVertex vertex, String key, V value) {
        super(vertex, key, value);
        if (value instanceof Map &&
                ((Map) value).containsKey("properties")) {
            this.properties = new HashMap<>();
            ((Map<String, Object>) ((Map) value).get("properties")).entrySet()
                    .forEach(entry -> this.createProperty(entry.getKey(), entry.getValue()));
            this.value = (V) ((Map) (((Map) value).get("value"))).get(key);
        }
    }

    @Override
    protected Map<String, Property> getPropertiesMap() {
        return properties;
    }

    @Override
    protected Property createProperty(String key, Object value) {
        UniProperty<Object> property = new UniProperty<>(this, key, value);
        this.properties.put(key, property);
        return property;
    }

    @Override
    public Property property(String key, Object value) {
        Property property = createProperty(key, value);
        PropertyQuery query = new PropertyQuery(this.vertex, property, PropertyQuery.Action.Add, null);
        graph.getControllerManager()
                .getControllers(PropertyQuery.PropertyController.class).forEach(controller -> controller.property(query));
        return property;
    }

    @Override
    public <U> Iterator<Property<U>> properties(String... propertyKeys) {
        List<String> keys = Arrays.asList(propertyKeys);
        return getPropertiesMap().entrySet().stream().filter(key -> {
            if (keys.isEmpty()) return true;
            return keys.contains(key.getKey());
        }).map(a -> (Property<U>)a.getValue()).iterator();
    }
}
