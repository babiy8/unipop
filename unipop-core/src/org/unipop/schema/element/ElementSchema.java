package org.unipop.schema.element;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.unipop.query.predicates.PredicatesHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 
 */
public interface ElementSchema<E extends Element> {
    Collection<E> fromFields(Map<String, Object> fields);
    Map<String, Object> toFields(E element);
    Set<String> toFields(Set<String> propertyKeys);
    PredicatesHolder toPredicates(PredicatesHolder predicatesHolder);
    String getFieldByPropertyKey(String key);

    default Set<ElementSchema> getChildSchemas() { return Collections.emptySet(); }
}
