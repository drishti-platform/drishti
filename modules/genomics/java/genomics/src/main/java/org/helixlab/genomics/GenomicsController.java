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
package org.helixlab.genomics;

import com.ash.drishti.kernel.api.module.ModuleApi;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Person;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The module's REST API under {@code /api/v1/genomics/}: what its pages draw (rows, never a layout). Every route asks the kernel as the
 * person ({@link GenomicsAccess}): they must open each kind the screens are made of (variant, sample, gene, cohort), and every row leaves
 * with their masks applied.
 */
@RestController
@ModuleApi("genomics")
public class GenomicsController {

    private final GenomicsData data;
    private final GenomicsAccess access;

    public GenomicsController(GenomicsData data, GenomicsAccess access) {
        this.data = data;
        this.access = access;
    }

    /** The variants, optionally of one gene or carried in one cohort, by position. */
    @GetMapping("/variants")
    public Map<String, Object> variants(@RequestParam(name = "gene", required = false) String gene,
            @RequestParam(name = "cohort", required = false) String cohort) {
        List<Map<String, Object>> rows = variantRows(person(), gene, cohort);
        return Map.of("rows", rows, "count", rows.size());
    }

    @GetMapping("/variants/{id}")
    public ResponseEntity<Map<String, Object>> variant(@PathVariable("id") String id) {
        Person p = person();
        Map<String, Object> v = data.now().variants().get(id);
        return v == null ? missing("variant", id) : ResponseEntity.ok(access.redact(p, GenomicsData.VARIANT, GenomicsData.variantRow(v)));
    }

    @GetMapping("/genes")
    public Map<String, Object> genes() {
        Person p = person();
        List<Map<String, Object>> rows = data.now().genes().keySet().stream().map(g -> access.redact(p, GenomicsData.GENE, data.geneRow(g))).toList();
        return Map.of("rows", rows, "count", rows.size());
    }

    /** A gene with its variants (the lollipop's rows). */
    @GetMapping("/genes/{id}")
    public ResponseEntity<Map<String, Object>> gene(@PathVariable("id") String id) {
        Person p = person();
        Map<String, Object> g = data.geneRow(id);
        if (g == null) {
            return missing("gene", id);
        }
        Map<String, Object> out = new LinkedHashMap<>(access.redact(p, GenomicsData.GENE, g));
        out.put("variants", variantRows(p, id, null));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/cohorts")
    public Map<String, Object> cohorts() {
        Person p = person();
        List<Map<String, Object>> rows = data.cohorts().values().stream().map(c -> access.redact(p, GenomicsData.COHORT, c)).toList();
        return Map.of("rows", rows, "count", rows.size());
    }

    @GetMapping("/cohorts/{id}")
    public ResponseEntity<Map<String, Object>> cohort(@PathVariable("id") String id) {
        Person p = person();
        Map<String, Object> c = data.cohorts().get(id);
        if (c == null) {
            return missing("cohort", id);
        }
        Map<String, Object> out = new LinkedHashMap<>(access.redact(p, GenomicsData.COHORT, c));
        out.put("variants", variantRows(p, null, id));
        return ResponseEntity.ok(out);
    }

    /** A refusal of the kernel's door: its status, code and words. */
    @ExceptionHandler(Kernel.Refused.class)
    public ResponseEntity<Map<String, Object>> refused(Kernel.Refused e) {
        return GenomicsAccess.refusal(e);
    }

    /** The request's person, refused unless they may open every kind the screens are made of. */
    private Person person() {
        return access.require(GenomicsData.KINDS);
    }

    private List<Map<String, Object>> variantRows(Person p, String gene, String cohort) {
        List<String> members = cohort == null ? null : members(cohort);
        List<Map<String, Object>> rows = new ArrayList<>();
        data.now().variants().values().stream()
                .filter(v -> gene == null || gene.equalsIgnoreCase(GenomicsData.text(v, "GENE")))
                .filter(v -> members == null || GenomicsData.carriers(v).stream().anyMatch(members::contains))
                .sorted(GenomicsData.byPosition())
                .forEach(v -> rows.add(access.redact(p, GenomicsData.VARIANT, GenomicsData.variantRow(v))));
        return rows;
    }

    private List<String> members(String cohort) {
        Map<String, Object> c = data.cohorts().get(cohort);
        List<String> out = new ArrayList<>();
        if (c != null && c.get("MEMBERS") instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> missing(String kind, String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "DRS-1001", "detail", "no " + kind + " " + id));
    }
}
