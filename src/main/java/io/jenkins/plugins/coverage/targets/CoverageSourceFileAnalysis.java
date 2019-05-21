package io.jenkins.plugins.coverage.targets;

import io.jenkins.plugins.coverage.source.code.JGitUtil;
import io.jenkins.plugins.coverage.source.code.SourceCodeFile;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@ExportedBean
public class CoverageSourceFileAnalysis implements Serializable {
    private static final long serialVersionUID = -3114190475749094715L;

    /**
     * Source file's root path.
     */
    private String rootPath;
    /**
     * the branch name.
     */
    private String branchName;
    /**
     * the last commit name.
     */
    private String lastCommitName;
    /**
     * the branch name matching regex. default is ".*"
     */
    private String branchNameMatchRegex = ".*";
    /**
     * relative coverage summary's level.
     */
    private CoverageElement level;

    public CoverageSourceFileAnalysis(String rootPath, String branchNameMatchRegex, CoverageElement level, String branchName, String lastCommitName) {
        this.rootPath = rootPath;
        this.branchNameMatchRegex = branchNameMatchRegex;
        this.level = level;
        this.branchName = branchName;
        this.lastCommitName = lastCommitName;
    }

    @Exported
    public String getRootPath() {
        return rootPath;
    }

    @Exported
    public String getBranchNameMatchRegex() {
        return branchNameMatchRegex;
    }

    @Exported
    public CoverageElement getLevel() {
        return level;
    }

    @Exported
    public String getBranchName() {
        return branchName;
    }

    @Exported
    public String getLastCommitName() {
        return lastCommitName;
    }

    public String getBranchCommitName() {
        return branchName + ":" + lastCommitName.substring(0, 8);
    }

    public String getTargetBranchCommitName(CoverageResult current) {
        CoverageSourceFileAnalysis targetVcs = resolveTargetVCS(current);
        if (targetVcs == null)
            return "";
        return targetVcs.getBranchCommitName();
    }

    public String getTargetLastCommitName(CoverageResult current) {
        CoverageSourceFileAnalysis vcs = resolveTargetVCS(current);
        return vcs == null ? "" : vcs.getLastCommitName();
    }

    public CoverageSourceFileAnalysis resolveTargetVCS(CoverageResult current) {
        CoverageResult previousResult = current;
        while ((previousResult = previousResult.getPreviousResult()) != null) {
            CoverageSourceFileAnalysis vcs = previousResult.getCoverageSourceFileAnalysis();
            if (vcs != null
                    && vcs.getBranchName().matches(this.branchNameMatchRegex)) {
                return vcs;
            }
        }
        return null;
    }

    public List<CoverageRelativeResultElement> getCoverageRelativeResultElement(CoverageResult report) {
        //  the relative summary feature is disabled
        final CoverageSourceFileAnalysis csfa = report.getCoverageSourceFileAnalysis();
        if (csfa == null)
            return Collections.emptyList();

        String lastVcsCommitName = csfa.getLastCommitName();
        if (null == lastVcsCommitName)
            return Collections.emptyList();
        return analysisGitCommitImpl(report, csfa.getRootPath(), csfa.getLevel(), csfa.getTargetLastCommitName(report), lastVcsCommitName);
    }

    public List<CoverageRelativeResultElement> analysisGitCommitImpl(CoverageResult report, String rootPath, CoverageElement level, String oldCommit, String newCommit) {
        List<SourceCodeFile> scbInfo = JGitUtil.analysisAddCodeBlock(rootPath, oldCommit, newCommit);
        if (scbInfo == null || scbInfo.isEmpty())
            return Collections.emptyList();

        List<CoverageRelativeResultElement> list = report.getChildrenResults()
                .parallelStream()
                .filter(cr -> CoverageElement.FILE.equals(cr.getElement()))
                .filter(cr -> cr.getPaint() != null)
                .map(cr -> scbInfo.stream()
                        .filter(p -> p.getPath().endsWith(cr.getRelativeSourcePath()))
                        .limit(1)
                        .findAny()
                        .map(scf -> {
                            int[] lines = scf.getBlocks()
                                    .stream()
                                    .flatMapToInt(block -> IntStream.rangeClosed((int) (block.getStartLine() + 1), (int) block.getEndLine())
                                            .filter(line -> cr.getPaint().isPainted(line))
                                    ).toArray();
                            //  absolute coverage
                            Map<CoverageElement, Ratio> results = new TreeMap<>();
                            Ratio crHitRatio = analysisLogicHitCoverage(cr.getPaint(), level, cr.getPaint().lines.keys());
                            results.put(CoverageElement.ABSOLUTE, crHitRatio);
                            //  newly code coverage
                            results.put(CoverageElement.RELATIVE, analysisLogicHitCoverage(cr.getPaint(), level, lines));
                            //  coverage change
                            CoverageResult pr = cr.getPreviousResult();
                            if (pr != null) {
                                Ratio prHitRatio = analysisLogicHitCoverage(pr.getPaint(), level, pr.getPaint().lines.keys());
                                if (prHitRatio.numerator != 0.0F) {
                                    results.put(CoverageElement.CHANGE, Ratio.create(crHitRatio.getPercentageFloat() - prHitRatio.getPercentageFloat(), 100.0F));
                                }
                            }
                            return new CoverageRelativeResultElement(cr.getName(), cr.getRelativeSourcePath(), results);
                        })
                        .orElse(null)
                )
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        //  Current Commit's Overview
        Map<CoverageElement, Ratio> overviewMap = list.stream()
                .map(re -> re.getResults().get(CoverageElement.RELATIVE))
                .filter(Objects::nonNull)
                .reduce((r1, r2) -> Ratio.create(r1.numerator + r2.numerator, r1.denominator + r2.denominator))
                .map(ratio -> {
                    Map<CoverageElement, Ratio> results = new TreeMap<>();
                    results.put(CoverageElement.ABSOLUTE, Ratio.ZERO);
                    results.put(CoverageElement.RELATIVE, ratio);
                    results.put(CoverageElement.CHANGE, Ratio.ZERO);
                    return results;
                })
                .orElse(Collections.emptyMap());
        list.add(0, new CoverageRelativeResultElement("Current Commit Overview", "Current Commit Overview", overviewMap));
        return list;
    }

    /**
     * calculate code coverage by analysis level.
     */
    private Ratio analysisLogicHitCoverage(CoveragePaint paint, CoverageElement level, int[] judgedLines) {
        if (CoverageElement.LINE.equals(level)) {
            long covered = Arrays.stream(judgedLines)
                    .parallel()
                    .filter(line -> paint.getHits(line) > 0)
                    .count();
            return Ratio.create(covered, judgedLines.length);
        } else {
            //  the covered logic hit means:
            //  1. when line don't have any logic branch, if hit count > 0, logic hit is 1, otherwise logic hit equals 0
            //  2. when line have logic branch, logic hit is branch coverage
            //
            long missedBranch = Arrays.stream(judgedLines)
                    .parallel()
                    .map(line -> {
                        int branchTotal = paint.getBranchTotal(line);
                        if (branchTotal > 0) {
                            return branchTotal - paint.getBranchCoverage(line);
                        } else {
                            return paint.getHits(line) > 0 ? 0 : 1;
                        }
                    })
                    .sum();
            long coveredBranch = Arrays.stream(judgedLines)
                    .parallel()
                    .map(line -> {
                        int covered = paint.getBranchCoverage(line);
                        if (covered <= 0 && paint.getHits(line) > 0)
                            return 1;
                        else
                            return covered;
                    })
                    .sum();
            return Ratio.create(coveredBranch, coveredBranch + missedBranch);
        }
    }
}
