/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.memlake;

import com.ash.lake.read.DayFiles;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.TableSnapshot;
import com.ash.lake.spi.AsOf;
import com.ash.lake.spi.Column;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The figures of one lake table (Admin, Lakes): rows, files, size, partitions, business dates and the last commit, read from the table's own
 * log through Drishti Lake's reader (no data file is opened). The table is the lake file's entry (its resolved location and format), so a
 * table the layout does not place under {@code <root>/<domain>/<table>} is read the same way. A table on an object store, or held in memory,
 * is listed without figures and says so. Also the lake's run log and the loads of one table (its {@code _drishti-lake/runs} summaries).
 */
final class LakeTableFigures {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private LakeTableFigures() {
    }

    /** The figures of the table at {@code location} (a local folder), as {@code format}; {@code detail} adds the dates, columns and promoted columns. */
    static Map<String, Object> read(String format, String location, boolean detail) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (location == null || location.contains("://")) {
            out.put("error", "the table is not on this server's local disk: its figures are read where it is");
            return out;
        }
        Path folder = Path.of(location).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder) || folder.getParent() == null || folder.getParent().getParent() == null) {
            out.put("error", "no table at " + folder);
            return out;
        }
        String name = folder.getFileName().toString();
        Map<String, String> settings = Map.of("root", folder.getParent().getParent().toString(), "domain", folder.getParent().getFileName().toString());
        try (LakeReader lake = com.ash.drishti.lakehost.LakeReads.open(format == null || format.isBlank() ? "delta" : format, settings)) {
            TableSnapshot s = lake.table(name).snapshot(AsOf.LATEST).orElse(null);
            if (s == null) {
                out.put("error", "no " + format + " table at " + folder);
                return out;
            }
            long rows = 0;
            long bytes = 0;
            int files = 0;
            TreeSet<String> keys = new TreeSet<>();
            TreeMap<String, long[]> partitions = new TreeMap<>();
            List<Map<String, Object>> byDate = new ArrayList<>();
            List<LocalDate> dates = new ArrayList<>(s.dates());
            for (LocalDate d : dates) {
                DayFiles day = s.day(d).orElse(null);
                if (day == null) {
                    continue;
                }
                rows += day.rows();
                bytes += day.bytes();
                files += day.files().size();
                Map<String, long[]> perDay = new TreeMap<>();
                for (var f : day.files()) {
                    Map<String, String> part = new TreeMap<>(f.partition());
                    part.remove("business_date");
                    keys.addAll(part.keySet());
                    if (!part.isEmpty()) {
                        String label = part.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
                        perDay.computeIfAbsent(label, k -> new long[1])[0] += Math.max(0, f.rows());
                        partitions.computeIfAbsent(label, k -> new long[1]);
                    }
                }
                if (detail) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", d.toString());
                    m.put("rows", day.rows());
                    m.put("files", day.files().size());
                    m.put("bytes", day.bytes());
                    m.put("partitions", perDay.entrySet().stream().map(e -> List.of(e.getKey(), e.getValue()[0])).toList());
                    byDate.add(0, m);                                              // newest first
                }
            }
            out.put("rows", rows);
            out.put("files", files);
            out.put("bytes", bytes);
            out.put("partitionBy", List.copyOf(keys));
            out.put("partitions", Math.max(partitions.size(), dates.size()));
            out.put("dates", dates.size());
            out.put("latestDate", dates.isEmpty() ? null : dates.get(dates.size() - 1).toString());
            out.put("firstDate", dates.isEmpty() ? null : dates.get(0).toString());
            out.put("version", s.version());
            Instant at = s.committed();
            out.put("lastBuild", at == null ? null : java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC).format(at));
            if (detail) {
                List<Map<String, Object>> columns = new ArrayList<>();
                Map<String, String> promoted = s.promoted();
                for (Column c : s.columns().all()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", c.name());
                    m.put("type", c.type().name().toLowerCase(java.util.Locale.ROOT));
                    m.put("partition", keys.contains(c.name()) || "business_date".equals(c.name()));
                    m.put("promoted", promoted.containsKey(c.name()));
                    columns.add(m);
                }
                out.put("columns", columns);
                out.put("partitionColumns", columns.stream().filter(c -> Boolean.TRUE.equals(c.get("partition"))).map(c -> c.get("name")).toList());
                out.put("byDate", byDate);
                out.put("idColumn", s.idColumn());
            }
        } catch (IOException | RuntimeException e) {
            out.put("error", String.valueOf(e.getMessage()).length() > 200 ? String.valueOf(e.getMessage()).substring(0, 200) : String.valueOf(e.getMessage()));
        }
        return out;
    }

    /** The newest {@code limit} loads of a table, from the lake root's run summaries ({@code _drishti-lake/runs/*.json}); newest first. */
    static List<Map<String, Object>> loads(Path root, String domain, String kind, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path runs = root.resolve("_drishti-lake").resolve("runs");
        if (!Files.isDirectory(runs)) {
            return out;
        }
        try (Stream<Path> files = Files.list(runs)) {
            List<Path> sorted = files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path f : sorted) {
                if (out.size() >= limit) {
                    break;
                }
                try {
                    var r = JSON.readTree(f.toFile());
                    if (!(domain + "/" + kind).equals(r.path("table").asText())) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("run", r.path("run").asText(null));
                    m.put("startedAt", r.path("startedAt").asText(null));
                    m.put("status", r.path("status").asText(null));
                    m.put("mode", r.path("mode").asText(null));
                    var rows = r.path("rows");
                    if (rows.isObject()) {
                        List<String> days = new ArrayList<>();
                        long n = 0;
                        for (var it = rows.fields(); it.hasNext();) {
                            var e = it.next();
                            days.add(e.getKey());
                            n += e.getValue().asLong();
                        }
                        java.util.Collections.sort(days);
                        m.put("dates", String.join(", ", days));
                        m.put("rows", n);
                    } else {
                        m.put("dates", r.path("asOf").asText(r.path("date").asText("")));
                        m.put("rows", rows.isNumber() ? rows.asLong() : null);
                    }
                    m.put("rejected", r.path("rejected").asLong(0));
                    m.put("seconds", r.path("timings").path("totalSeconds").isMissingNode() ? null : r.path("timings").path("totalSeconds").asDouble());
                    m.put("engine", r.path("engine").asText(null));
                    m.put("error", r.path("error").asText(null));
                    out.add(m);
                } catch (IOException | RuntimeException e) {
                    // a summary that does not read is skipped, as a log line is
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** The last {@code limit} lines of the lake root's run log. */
    static List<String> runLog(Path root, int limit) {
        for (String dir : new String[] {"_drishti-lake", ".drishti-lake"}) {
            Path log = root.resolve(dir).resolve("runs.log");
            if (Files.isRegularFile(log)) {
                try {
                    List<String> all = Files.readAllLines(log);
                    return all.subList(Math.max(0, all.size() - limit), all.size());
                } catch (IOException e) {
                    return List.of();
                }
            }
        }
        return List.of();
    }
}
