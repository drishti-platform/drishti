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
package com.ash.drishti.banking.var;

import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.kernel.api.services.EngineAnswer;
import com.ash.drishti.kernel.api.services.EngineColumn;
import com.ash.drishti.kernel.api.services.EngineTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * R2 (ENGINE_INTEGRATION_DESIGN 18.1, EN-68): the market-risk engine's index of one date (its outputs {@code scope-index},
 * {@code scope-positions}, {@code scope-position-values}, {@code scope-nodes}, {@code scope-words}, {@code scope-limits}) as a book the
 * host's resolver reads. The scope grammar, the saved and system baskets and the resolution are Drishti's; the rows, their order and the
 * positions the rules derive are the engine's. The ordinals the resolver gives are the row set the engine path asks with.
 */
final class EngineScopeBook implements ScopeResolver.Book, EngineAnswer.Sized {

    private final String[] trades;
    private final int[] positionOf;
    private final boolean[] supplied;
    private final boolean[] cancelled;
    private final Map<String, String[]> tradeTexts = new HashMap<>();
    private final Map<String, Integer> tradeUpper = new HashMap<>();
    private final Map<String, Integer> tradeExact = new HashMap<>();
    private final String[] positionIds;
    private final int[][] positionRuns;
    private final Map<String, Integer> positionUpper = new HashMap<>();
    private final Map<String, Integer> positionExact = new HashMap<>();
    /** The positions' values read in place from {@code scope-position-values} (one copy: the answer's): each position's rows. */
    private final EngineColumn valueColumn;
    private final EngineColumn valueText;
    private final EngineColumn valueNumber;
    private final int[] valueStart;
    private final int[] valueRows;
    private final Map<String, OrdinalSet> nodes = new LinkedHashMap<>();
    private final Map<String, Double> nodeLimits = new HashMap<>();
    private final Map<String, String> roots = new LinkedHashMap<>();
    final Map<String, String> words = new LinkedHashMap<>();
    final Map<String, Double> basketLimits = new LinkedHashMap<>();
    /** Each node's name and its child nodes, in the engine's node order (the P&amp;L path's parts). */
    final Map<String, String> names = new HashMap<>();
    final Map<String, String> levels = new HashMap<>();
    final Map<String, List<String>> children = new HashMap<>();

    /** About the bytes the book holds (the host's answer cache counts it with the index answer it is shaped from). */
    private final long bytes;

    /** The book of an index answer, its tables read in place (no row boxed). */
    EngineScopeBook(EngineAnswer answer) {
        EngineTable idx = answer.table("scope-index");
        int n = idx == null ? 0 : idx.rows();
        trades = new String[n];
        positionOf = new int[n];
        supplied = new boolean[n];
        cancelled = new boolean[n];
        String[] cpty = new String[n];
        String[] le = new String[n];
        if (n > 0) {                                                    // each column resolved once, its cells read by row
            EngineColumn ord = idx.column("ORDINAL");
            EngineColumn trade = idx.column("TRADE_ID");
            EngineColumn position = idx.column("POSITION");
            EngineColumn sup = idx.column("SUPPLIED");
            EngineColumn can = idx.column("CANCELLED");
            EngineColumn cp = idx.column("COUNTERPARTY");
            EngineColumn entity = idx.column("LEGAL_ENTITY");
            for (int r = 0; r < n; r++) {
                int o = (int) ord.integer(r);
                trades[o] = String.valueOf(trade.value(r));
                positionOf[o] = (int) position.integer(r);
                supplied[o] = !sup.isNull(r) && sup.flag(r);
                cancelled[o] = !can.isNull(r) && can.flag(r);
                cpty[o] = cp.text(r);
                le[o] = entity.text(r);
            }
        }
        tradeTexts.put("COUNTERPARTY", cpty);
        tradeTexts.put("LEGAL_ENTITY", le);
        long b = 256 + 120L * n;
        for (int o = 0; o < n; o++) {
            tradeExact.putIfAbsent(trades[o], o);
            tradeUpper.putIfAbsent(trades[o].toUpperCase(Locale.ROOT), o);
        }
        EngineTable pos = answer.table("scope-positions");
        int np = pos == null ? 0 : pos.rows();
        positionIds = new String[np];
        positionRuns = new int[np][];
        if (np > 0) {
            EngineColumn position = pos.column("POSITION");
            EngineColumn id = pos.column("POSITION_ID");
            EngineColumn runs = pos.column("RUNS");
            for (int r = 0; r < np; r++) {
                int p = (int) position.integer(r);
                positionIds[p] = String.valueOf(id.value(r));
                positionRuns[p] = ints(runs.list(r));
                positionExact.putIfAbsent(positionIds[p], p);
                positionUpper.putIfAbsent(positionIds[p].toUpperCase(Locale.ROOT), p);
                b += 160 + 4L * positionRuns[p].length;
            }
        }
        EngineTable pv = answer.table("scope-position-values");
        int nv = pv == null ? 0 : pv.rows();
        valueStart = new int[np + 1];
        valueRows = new int[nv];
        if (nv > 0) {                                                   // each position's rows, in row order (a counting sort: ints only)
            EngineColumn position = pv.column("POSITION");
            int[] of = new int[nv];
            for (int r = 0; r < nv; r++) {
                of[r] = (int) position.integer(r);
                valueStart[of[r] + 1]++;
            }
            for (int p = 0; p < np; p++) {
                valueStart[p + 1] += valueStart[p];
            }
            int[] next = java.util.Arrays.copyOf(valueStart, np);
            for (int r = 0; r < nv; r++) {
                valueRows[next[of[r]]++] = r;
            }
            valueColumn = pv.column("COLUMN");
            valueText = pv.column("TEXT");
            valueNumber = pv.column("NUMBER");
        } else {
            valueColumn = null;
            valueText = null;
            valueNumber = null;
        }
        b += 4L * (np + 1) + 4L * nv;
        EngineTable nodeTable = answer.table("scope-nodes");
        for (int r = 0; nodeTable != null && r < nodeTable.rows(); r++) {
            String id = String.valueOf(nodeTable.value("NODE_ID", r));
            int[] runs = ints(nodeTable.list("RUNS", r));
            OrdinalSet.Builder ob = new OrdinalSet.Builder();
            for (int i = 0; i + 1 < runs.length; i += 2) {
                ob.addRange(runs[i], runs[i + 1]);
            }
            nodes.put(id, ob.build());
            if (!nodeTable.isNull("LIMIT", r)) {
                nodeLimits.put(id, nodeTable.number("LIMIT", r));
            }
            names.put(id, nodeTable.isNull("NAME", r) ? id : String.valueOf(nodeTable.value("NAME", r)));
            if (!nodeTable.isNull("LEVEL", r)) {
                levels.put(id, String.valueOf(nodeTable.value("LEVEL", r)));
            }
            if (!nodeTable.isNull("PARENT", r)) {
                children.computeIfAbsent(String.valueOf(nodeTable.value("PARENT", r)), k -> new ArrayList<>()).add(id);
            } else {
                roots.putIfAbsent(String.valueOf(nodeTable.value("HIERARCHY", r)), id);
            }
            b += 400 + 4L * runs.length;
        }
        EngineTable w = answer.table("scope-words");
        for (int r = 0; w != null && r < w.rows(); r++) {
            words.put(String.valueOf(w.value("WORD", r)), String.valueOf(w.value("HIERARCHY", r)));
        }
        EngineTable lim = answer.table("scope-limits");
        for (int r = 0; lim != null && r < lim.rows(); r++) {
            basketLimits.put(String.valueOf(lim.value("BASKET_ID", r)), lim.isNull("LIMIT", r) ? Double.NaN : lim.number("LIMIT", r));
        }
        this.bytes = b;
    }

    @Override
    public long bytes() {
        return bytes;
    }

    private static int[] ints(double[] d) {
        if (d == null) {
            return new int[0];
        }
        int[] out = new int[d.length];
        for (int i = 0; i < d.length; i++) {
            out[i] = (int) d[i];
        }
        return out;
    }

    int rows() {
        return trades.length;
    }

    /** A node's limit (USD), NaN when it has none. */
    double nodeLimit(String node) {
        return nodeLimits.getOrDefault(node, Double.NaN);
    }

    @Override
    public OrdinalSet members(String node) {
        return nodes.get(node);
    }

    @Override
    public List<String> hierarchies() {
        return List.copyOf(roots.keySet());
    }

    @Override
    public String rootId(String hierarchy) {
        return roots.get(hierarchy);
    }

    @Override
    public int positionIndexAnyCase(String id) {
        Integer p = positionExact.get(id);
        if (p == null) {
            p = positionUpper.get(id.toUpperCase(Locale.ROOT));
        }
        return p == null ? -1 : p;
    }

    @Override
    public void forEachPositionRun(int index, OrdinalSet.RunConsumer f) {
        int[] r = positionRuns[index];
        for (int i = 0; i + 1 < r.length; i += 2) {
            f.run(r[i], r[i + 1]);
        }
    }

    @Override
    public int tradeOrdinalAnyCase(String id) {
        Integer o = tradeExact.get(id);
        if (o == null) {
            o = tradeUpper.get(id.toUpperCase(Locale.ROOT));
        }
        return o == null ? -1 : o;
    }

    @Override
    public boolean supplied(int ordinal) {
        return ordinal >= 0 && supplied[ordinal];
    }

    @Override
    public OrdinalSet tradesWhere(String column, Collection<String> values) {
        String[] t = tradeTexts.get(column);
        OrdinalSet.Builder b = new OrdinalSet.Builder();
        if (t == null) {
            return b.build();
        }
        for (int o = 0; o < t.length; o++) {
            if (t[o] != null && !cancelled[o]) {
                for (String v : values) {
                    if (t[o].equalsIgnoreCase(v)) {
                        b.add(o);
                        break;
                    }
                }
            }
        }
        return b.build();
    }

    @Override
    public int positionCount() {
        return positionIds.length;
    }

    @Override
    public Object positionValue(String column, int position) {
        if (valueColumn == null || position < 0 || position >= valueStart.length - 1) {
            return null;
        }
        for (int i = valueStart[position]; i < valueStart[position + 1]; i++) {
            int r = valueRows[i];
            if (column.equals(valueColumn.text(r))) {
                Object v = !valueText.isNull(r) ? valueText.text(r) : valueNumber.isNull(r) ? null : (Object) valueNumber.number(r);
                if (v instanceof String t && column.equals("TAGS")) {    // a list column, written a|b|c (Columns.value)
                    return t.isEmpty() ? List.of() : List.of(t.split("\\|"));
                }
                return v;
            }
        }
        return null;
    }
}
