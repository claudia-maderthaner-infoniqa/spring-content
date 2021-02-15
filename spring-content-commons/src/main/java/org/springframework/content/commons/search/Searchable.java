package org.springframework.content.commons.search;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface Searchable<T> {

    Iterable<T> search(String queryString);

    Page<T> search(String queryString, Pageable pageable);

    @Deprecated
    Iterable<T> findKeyword(String query);

    @Deprecated
    Iterable<T> findAllKeywords(String... terms);

    @Deprecated
    Iterable<T> findAnyKeywords(String... terms);

    @Deprecated
    Iterable<T> findKeywordsNear(int proximity, String... terms);

    @Deprecated
    Iterable<T> findKeywordStartsWith(String term);

    @Deprecated
    Iterable<T> findKeywordStartsWithAndEndsWith(String a, String b);

    @Deprecated
    Iterable<T> findAllKeywordsWithWeights(String[] terms, double[] weights);
}
