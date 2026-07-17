                        package com.example.urlshortener;

                        import java.time.Instant;
                        import java.util.Map;
                        import java.util.concurrent.ConcurrentHashMap;

                        public class RateLimiter {
                            private static final int LIMIT = 100;
                            private final Map<String, Window> windows = new ConcurrentHashMap<>();

                            public boolean allow(String ip) {
                                Window w = windows.compute(ip, (k, existing) ->
                                        existing == null || existing.isExpired()
                                                ? new Window() : existing);
                                return w.increment() <= LIMIT;
                            }

                            private static final class Window {
                                private final Instant start = Instant.now();
                                private int count = 0;

                                boolean isExpired() {
                                    return start.plusSeconds(60).isBefore(Instant.now());
                                }

                                synchronized int increment() {
                                    return ++count;
                                }
                            }
                        }
