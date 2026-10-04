package com.company.buildanalyzer.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JenkinsBuildUrlTest {

    @Test
    void parsesATopLevelJob() {
        assertThat(JenkinsBuildUrl.parse("https://jenkins.company.com/job/UI-Test/125/"))
                .isEqualTo(new JenkinsBuildUrl("UI-Test", 125, "https://jenkins.company.com/job/UI-Test/125/"));
    }

    @Test
    void parsesJobsInFoldersOfAnyDepth() {
        assertThat(JenkinsBuildUrl.parse("https://jenkins.company.com/job/Team/job/UI-Test/125/"))
                .isEqualTo(new JenkinsBuildUrl("Team/UI-Test", 125, "https://jenkins.company.com/job/Team/job/UI-Test/125/"));
        assertThat(JenkinsBuildUrl.parse("https://jenkins.company.com/job/Team/job/UI/job/Regression/7").jobPath())
                .isEqualTo("Team/UI/Regression");
    }

    @Test
    void dropsTrailingPartsQueryAndFragmentAndKeepsAContextPathAndPort() {
        assertThat(JenkinsBuildUrl.parse("  http://localhost:8080/jenkins/job/UI-Test/7/console?x=1#footer "))
                .isEqualTo(new JenkinsBuildUrl("UI-Test", 7, "http://localhost:8080/jenkins/job/UI-Test/7/"));
    }

    @Test
    void decodesEncodedJobNamesButKeepsTheUrlEncoded() {
        JenkinsBuildUrl build = JenkinsBuildUrl.parse("https://jenkins.company.com/job/My%20Tests/job/C++/12/");

        assertThat(build.jobPath()).isEqualTo("My Tests/C++");
        assertThat(build.url()).isEqualTo("https://jenkins.company.com/job/My%20Tests/job/C++/12/");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a url",
            "ftp://jenkins.company.com/job/UI-Test/1/",
            "https://github.com/org/repo",
            "https://jenkins.company.com/job/UI-Test/",
            "https://jenkins.company.com/job/UI-Test/0/",
            "https://jenkins.company.com/job/UI-Test/lastFailedBuild/",
            "https://jenkins.company.com/blue/organizations/jenkins/UI-Test/detail/UI-Test/125/pipeline",
            "https://jenkins.company.com/job/Repo/job/feature%2Flogin/7/",
    })
    void rejectsWhatIsNotAJenkinsBuildUrl(String text) {
        assertThatThrownBy(() -> JenkinsBuildUrl.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }
}
