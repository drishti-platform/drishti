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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The showcase's REST API under {@code /api/v1/genomics/} (modules/genomics/docs/guide.md section 9): what the genomics board and
 * the protein workbench draw, every figure made here, every answer with its Explain block. A selection of samples travels as
 * {@code cluster=}, {@code box=x0,x1,y0,y1} or {@code samples=} on any call it applies to ({@link Selection}). All calls are GET. Every
 * route asks the kernel as the person first ({@link GenomicsAccess}): the board's routes need every kind of the genomics showcase, the
 * workbench's every kind of protein engineering; a person who may not open them is refused with the kernel's status and words.
 */
@RestController
@ModuleApi("genomics")
public class ShowcaseController {

    private final ShowcaseData data;
    private final GenomicsBoard board;
    private final ProteinBench bench;
    private final GenomicsAccess access;

    public ShowcaseController(ShowcaseData data, GenomicsBoard board, ProteinBench bench, GenomicsAccess access) {
        this.data = data;
        this.board = board;
        this.bench = bench;
        this.access = access;
    }

    /** The genomics board's door: every kind of the genomics showcase, as the person. */
    private void board() {
        access.require(ShowcaseData.GENOMICS_KINDS);
    }

    /** The protein workbench's door: every kind of protein engineering, as the person. */
    private void bench() {
        access.require(ShowcaseData.PROTEIN_KINDS);
    }

    /** What is loaded: rows per kind, the kinds with none; {@code refresh=true} reads the kinds again. */
    @GetMapping("/showcase/status")
    public Map<String, Object> status(@RequestParam(name = "refresh", required = false) Boolean refresh) {
        board();
        bench();
        if (Boolean.TRUE.equals(refresh)) {
            data.now(true);
        }
        return board.status();
    }

    @GetMapping("/cohorts/{id}/summary")
    public Map<String, Object> summary(@PathVariable("id") String id, @RequestParam(name = "gene", required = false) String gene,
            @RequestParam(name = "cluster", required = false) String cluster, @RequestParam(name = "box", required = false) String box,
            @RequestParam(name = "samples", required = false) String samples) {
        board();
        return board.summary(Selection.of(id, cluster, box, samples), gene);
    }

    @GetMapping("/associations")
    public Map<String, Object> associations(@RequestParam(name = "trait", required = false) String trait) {
        board();
        return board.associations(trait);
    }

    @GetMapping("/cohorts/{id}/oncoprint")
    public Map<String, Object> oncoprint(@PathVariable("id") String id, @RequestParam(name = "genes", defaultValue = "24") int genes,
            @RequestParam(name = "gene", required = false) String gene, @RequestParam(name = "cluster", required = false) String cluster,
            @RequestParam(name = "box", required = false) String box, @RequestParam(name = "samples", required = false) String samples) {
        board();
        return board.oncoprint(Selection.of(id, cluster, box, samples), genes, gene);
    }

    @GetMapping("/cohorts/{id}/samples/map")
    public Map<String, Object> sampleMap(@PathVariable("id") String id, @RequestParam(name = "cluster", required = false) String cluster,
            @RequestParam(name = "box", required = false) String box, @RequestParam(name = "samples", required = false) String samples) {
        board();
        return board.sampleMap(Selection.of(id, cluster, box, samples));
    }

    @GetMapping("/genes/{id}/lollipop")
    public Map<String, Object> lollipop(@PathVariable("id") String id, @RequestParam(name = "cohort", required = false) String cohort,
            @RequestParam(name = "cluster", required = false) String cluster, @RequestParam(name = "box", required = false) String box,
            @RequestParam(name = "samples", required = false) String samples, @RequestParam(name = "assay", required = false) String assay) {
        board();
        return board.lollipop(id, Selection.of(cohort, cluster, box, samples), assay);
    }

    @GetMapping("/proteins")
    public Map<String, Object> proteins() {
        bench();
        return bench.proteins();
    }

    @GetMapping("/proteins/{id}/dms")
    public Map<String, Object> dms(@PathVariable("id") String id, @RequestParam(name = "assay", required = false) String assay) {
        bench();
        return bench.dms(id, assay);
    }

    @GetMapping("/proteins/{id}/structure")
    public Map<String, Object> structure(@PathVariable("id") String id) {
        bench();
        return bench.structure(id);
    }

    @GetMapping("/proteins/{id}/residues/{pos}")
    public Map<String, Object> residue(@PathVariable("id") String id, @PathVariable("pos") int pos) {
        bench();
        return bench.residue(id, pos);
    }

    @GetMapping("/proteins/{id}/designs")
    public Map<String, Object> designs(@PathVariable("id") String id, @RequestParam(name = "residue", required = false) Integer residue,
            @RequestParam(name = "aa", required = false) String aa) {
        bench();
        return bench.campaign(id, residue, aa);
    }

    @GetMapping("/proteins/{id}/designs/{design}")
    public Map<String, Object> design(@PathVariable("id") String id, @PathVariable("design") String design) {
        bench();
        return bench.design(id, design);
    }

    @GetMapping("/proteins/{id}/rounds")
    public Map<String, Object> rounds(@PathVariable("id") String id) {
        bench();
        return bench.rounds(id);
    }

    @GetMapping("/proteins/{id}/leaderboard")
    public Map<String, Object> leaderboard(@PathVariable("id") String id, @RequestParam(name = "round", required = false) Integer round,
            @RequestParam(name = "n", defaultValue = "20") int n) {
        bench();
        return bench.leaderboard(id, round, n);
    }

    /**
     * Explain of a mark: {@code variant/<id>} (its carriers in the selection and the scan's score), {@code site/<id>} (its tests),
     * {@code cluster/<name>}, {@code cell/<pos>:<aa>?protein=} (the scan's measurements), {@code design/<id>?protein=},
     * {@code residue/<pos>?protein=}.
     */
    @GetMapping("/explain/{what}/{id}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> explain(@PathVariable("what") String what, @PathVariable("id") String id,
            @RequestParam(name = "cohort", required = false) String cohort, @RequestParam(name = "cluster", required = false) String cluster,
            @RequestParam(name = "box", required = false) String box, @RequestParam(name = "samples", required = false) String samples,
            @RequestParam(name = "assay", required = false) String assay, @RequestParam(name = "protein", required = false) String protein) {
        if (List.of("cell", "design", "residue").contains(what)) {
            bench();
        } else {
            board();
        }
        switch (what) {
            case "variant":
                return board.explainVariant(id, Selection.of(cohort, cluster, box, samples), assay);
            case "site":
                return board.explainSite(id);
            case "cluster":
                return board.explainCluster(id, cohort);
            case "cell": {
                String[] p = id.split(":", 2);
                if (p.length != 2) {
                    throw new IllegalArgumentException("a cell is <position>:<amino acid>, not " + id);
                }
                return bench.explainCell(need(protein), Integer.parseInt(p[0]), p[1]);
            }
            case "design":
                return (Map<String, Object>) bench.design(need(protein), id).get("explain");
            case "residue":
                return (Map<String, Object>) bench.residue(need(protein), Integer.parseInt(id)).get("explain");
            default:
                throw new IllegalArgumentException("Explain knows variant, site, cluster, cell, design and residue, not " + what);
        }
    }

    private static String need(String protein) {
        if (protein == null || protein.isBlank()) {
            throw new IllegalArgumentException("say which protein (protein=HLXK7)");
        }
        return protein;
    }

    /** A refusal of the kernel's door: its status, code and words. */
    @ExceptionHandler(com.ash.drishti.kernel.api.services.Kernel.Refused.class)
    public ResponseEntity<Map<String, Object>> refusedByKernel(com.ash.drishti.kernel.api.services.Kernel.Refused e) {
        return GenomicsAccess.refusal(e);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> missing(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "DRS-1001", "detail", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> refused(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "DRS-1002", "detail", String.valueOf(e.getMessage())));
    }
}
