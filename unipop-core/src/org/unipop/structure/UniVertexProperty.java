package org.unipop.structure;

import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class UniVertexProperty<V> extends UniElement implements VertexProperty<V> {

    protected final UniVertex vertex;
    protected final String key;
    protected V value;

    public UniVertexProperty(final UniVertex vertex, final String key, final V value) {
        super(new HashMap<String, Object>(){{put(T.label.getAccessor(), value);}},vertex.graph);
        this.vertex = vertex;
        this.key = key;
        this.value = value;
    }

    @Override
    public String key() {
        return this.key;
    }

    @Override
    public V value() {
        return this.value;
    }

    @Override
    public boolean isPresent() {
        return true;
    }

    @Override
    public String toString() {
        return StringFactory.propertyString(this);
    }

    @Override
    protected Map<String, Property> getPropertiesMap() {
        throw VertexProperty.Exceptions.multiPropertiesNotSupported();
    }

    @Override
    protected String getDefaultLabel() {
        return label();
    }

    @Override
    public Object id() {
        return (long) (this.key.hashCode() + this.value.hashCode() + this.vertex.id().hashCode());
    }

    @Override
    public boolean equals(final Object object) {
        return ElementHelper.areEqual(this, object);
    }

    @Override
    protected Property createProperty(String key, Object value) {
        throw VertexProperty.Exceptions.multiPropertiesNotSupported();
    }

    @Override
    public int hashCode() {
        return ElementHelper.hashCode((Element) this);
    }

    @Override
    public <U> Property<U> property(final String key, final U value) {
        throw VertexProperty.Exceptions.multiPropertiesNotSupported();
    }

    @Override
    public Vertex element() {
        return this.vertex;
    }

    @Override
    public <U> Iterator<Property<U>> properties(String... propertyKeys) {
        throw VertexProperty.Exceptions.multiPropertiesNotSupported();
    }

    @Override
    public void remove() {
        vertex.removeProperty(this);
    }
}