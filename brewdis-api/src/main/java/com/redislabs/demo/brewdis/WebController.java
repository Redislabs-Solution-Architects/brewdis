package com.redislabs.demo.brewdis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redislabs.demo.brewdis.Config.StompConfig;
import com.redislabs.mesclun.StatefulRedisModulesConnection;
import com.redislabs.mesclun.search.*;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.redislabs.demo.brewdis.BrewdisField.*;

@RestController
@RequestMapping(path = "/api")
@CrossOrigin
@Slf4j
class WebController {

    @Autowired
    private Config config;
    @Autowired
    private StatefulRedisModulesConnection<String, String> connection;
    @Autowired
    private InventoryGenerator generator;
    @Autowired
    private DataLoader data;
    private ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/config/stomp")
    public StompConfig stompConfig() {
        return config.getStomp();
    }

    public static @Data
    class Query {
        private String query = "*";
        private String sortByField;
        private String sortByDirection = "Ascending";
        private long pageIndex = 0;
        private long pageSize = 100;

        public long getOffset() {
            return pageIndex * pageSize;
        }

    }

    public static @Data
    class ResultsPage {
        private long count;
        private SearchResults<String, String> results;
        private float duration;
        private long pageIndex;
        private long pageSize;
    }

    @PostMapping("/products")
    public ResultsPage products(@RequestBody Query query,
                                @RequestParam(name = "longitude", required = true) Double longitude,
                                @RequestParam(name = "latitude", required = true) Double latitude, HttpSession session) {
        log.info("Searching for products around lon={} lat={}", longitude, latitude);
        SearchOptions.SearchOptionsBuilder options = SearchOptions.builder()
                .highlight(SearchOptions.Highlight.builder().field(PRODUCT_NAME).field(PRODUCT_DESCRIPTION)
                        .field(CATEGORY_NAME).field(STYLE_NAME).field(BREWERY_NAME)
                        .tags(SearchOptions.Tags.builder().open("<mark>").close("</mark>").build()).build())
                .limit(SearchOptions.Limit.offset(query.getOffset()).num(query.getPageSize()));
        if (query.getSortByField() != null) {
            options.sortBy(SearchOptions.SortBy.field(query.getSortByField()).order(Order.valueOf(query.getSortByDirection())));
        }
        String queryString = query.getQuery() == null || query.getQuery().length() == 0 ? "*" : query.getQuery();
        long startTime = System.currentTimeMillis();
        SearchResults<String, String> searchResults = connection.sync().search(config.getProduct().getIndex(),
                queryString, options.build());
        long endTime = System.currentTimeMillis();
        List<String> skus = searchResults.stream().map(r -> r.get(PRODUCT_ID)).collect(Collectors.toList());
        List<String> stores = connection.sync().search(config.getStore().getIndex(), geoCriteria(longitude, latitude))
                .stream().map(r -> r.get(STORE_ID)).collect(Collectors.toList());
        generator.add(session.getId(), stores, skus);
        ResultsPage results = new ResultsPage();
        results.setCount(searchResults.getCount());
        results.setResults(searchResults);
        results.setPageIndex(query.getPageIndex());
        results.setPageSize(query.getPageSize());
        results.setDuration(((float) (endTime - startTime)) / 1000);
        return results;
    }

    @Builder
    public static @Data
    class Style {

        private String id;
        private String name;

    }

    @GetMapping("/styles")
    public List<Style> styles(@RequestParam(name = "category", defaultValue = "", required = false) String category) {
        return data.getStyles().get(category);
    }

    @Builder
    public static @Data
    class Category {

        private String id;
        private String name;

    }

    @GetMapping("/categories")
    public List<Category> categories() {
        return data.getCategories();
    }

    @GetMapping("/inventory")
    public SearchResults<String, String> inventory(@RequestParam(name = "store", required = false) String store) {
        String query = "@" + AVAILABLE_TO_PROMISE + ":[0 inf]";
        if (store != null) {
            query += " " + config.tag(STORE_ID, store);
        }
        return connection.sync().search(config.getInventory().getIndex(), query,
                SearchOptions.builder().sortBy(SearchOptions.SortBy.field(STORE_ID).order(Order.ASC))
                        .limit(SearchOptions.Limit.offset(0).num(config.getInventory().getSearchLimit())).build());
    }

    @GetMapping("/availability")
    public SearchResults<String, String> availability(@RequestParam(name = "sku", required = false) String sku,
                                                      @RequestParam(name = "longitude", required = true) Double longitude,
                                                      @RequestParam(name = "latitude", required = true) Double latitude) {
        String query = geoCriteria(longitude, latitude);
        if (sku != null) {
            query += " " + config.tag(PRODUCT_ID, sku);
        }
        log.info("Searching for availability: {}", query);
        SearchResults<String, String> results = connection.sync().search(config.getInventory().getIndex(), query,
                SearchOptions.builder().limit(SearchOptions.Limit.offset(0).num(config.getInventory().getSearchLimit()))
                        .build());
        results.forEach(r -> r.put(LEVEL, config.getInventory().level(availableToPromise(r))));
        return results;

    }

    private int availableToPromise(Document<String, String> result) {
        if (result.containsKey(AVAILABLE_TO_PROMISE)) {
            return Integer.parseInt(result.get(AVAILABLE_TO_PROMISE));
        }
        return 0;
    }

    private String geoCriteria(Double longitude, Double latitude) {
        return "@" + LOCATION + ":[" + longitude + " " + latitude + " " + config.getAvailabilityRadius() + "]";
    }

    public static @Data
    class BrewerySuggestion {
        private String id;
        private String name;
        private String icon;
    }

    public static @Data
    class BrewerySuggestionPayload {
        private String id;
        private String icon;
    }

    @GetMapping("/breweries")
    public Stream<BrewerySuggestion> suggestBreweries(
            @RequestParam(name = "prefix", defaultValue = "", required = false) String prefix) {
        List<Suggestion<String>> results = connection.sync().sugget(config.getProduct().getBrewery().getIndex(),
                prefix, SuggetOptions.builder().withPayloads(true).max(20l)
                        .fuzzy(config.getProduct().getBrewery().isFuzzy()).build());
        return results.stream().map(s -> {
            BrewerySuggestion suggestion = new BrewerySuggestion();
            suggestion.setName(s.getString());
            BrewerySuggestionPayload payload;
            try {
                payload = mapper.readValue(s.getPayload(), BrewerySuggestionPayload.class);
                suggestion.setId(payload.getId());
                suggestion.setIcon(payload.getIcon());
            } catch (Exception e) {
                log.error("Could not deserialize brewery payload {}", s.getPayload(), e);
            }
            return suggestion;
        });
    }

    @GetMapping("/foods")
    public Stream<String> suggestFoods(
            @RequestParam(name = "prefix", defaultValue = "", required = false) String prefix) {
        List<Suggestion<String>> results = connection.sync().sugget(config.getProduct().getFoodPairings().getIndex(),
                prefix, SuggetOptions.builder().withPayloads(true).max(20l)
                        .fuzzy(config.getProduct().getFoodPairings().isFuzzy()).build());
        return results.stream().map(s -> s.getString());
    }

}