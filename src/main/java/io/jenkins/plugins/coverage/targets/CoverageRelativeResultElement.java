package io.jenkins.plugins.coverage.targets;

import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 */
@ExportedBean()
public class CoverageRelativeResultElement implements Serializable {

    private static final long serialVersionUID = 4649039844700570525L;

    private String name;
    private String relativeSourcePath;
    private Map<CoverageElement, Ratio> results;

    public CoverageRelativeResultElement(String name, String relativeSourcePath, Map<CoverageElement, Ratio> results) {
        this.name = name;
        this.relativeSourcePath = relativeSourcePath;
        this.results = results;
    }

    @Exported
    public String getFilePath() {
        if (relativeSourcePath == null || relativeSourcePath.isEmpty())
            return name;
        return relativeSourcePath;
    }

    @Exported
    public String getName() {
        return name;
    }

    @Exported
    public String getRelativeSourcePath() {
        return relativeSourcePath;
    }

    public Map<CoverageElement, Ratio> getResults() {
        return results;
    }

    @Exported
    public List<CoverageTreeElement> getCoverageResults() {
        return results.entrySet()
                .stream()
                .map(entry -> new CoverageTreeElement(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }
}
