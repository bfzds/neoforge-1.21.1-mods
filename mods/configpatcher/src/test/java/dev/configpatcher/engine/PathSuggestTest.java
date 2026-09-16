package dev.configpatcher.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathSuggestTest {

    @Test
    void findsRenamedEntryIgnoringSeparatorsAndCase() {
        List<List<String>> known = List.of(
                List.of("features", "enable_thing"),
                List.of("limits", "maxMachines"),
                List.of("misc", "somethingElse")
        );
        List<String> suggestions = PathSuggest.suggest(known, List.of("features", "enableThing"), 3);
        assertEquals(List.of("features.enable_thing"), suggestions);
    }

    @Test
    void findsMovedSection() {
        List<List<String>> known = List.of(
                List.of("limits", "maxMachines"),
                List.of("features", "completelyDifferent")
        );
        List<String> suggestions = PathSuggest.suggest(known, List.of("limits", "max_machines"), 3);
        assertEquals(List.of("limits.maxMachines"), suggestions);
    }

    @Test
    void returnsEmptyWhenNothingIsClose() {
        List<List<String>> known = List.of(List.of("aaa", "bbb"));
        assertTrue(PathSuggest.suggest(known, List.of("zzzzzzzzzz"), 3).isEmpty());
    }

    @Test
    void levenshteinIsSane() {
        assertEquals(0, PathSuggest.levenshtein("abc", "abc"));
        assertEquals(1, PathSuggest.levenshtein("abc", "abd"));
        assertEquals(3, PathSuggest.levenshtein("abc", ""));
    }
}
