package com.company.buildanalyzer.application.port.out;

/**
 * Outbound port for retrieving build data from a CI source.
 * The application core depends only on this contract; the concrete source
 * (Jenkins, file, etc.) lives in the infrastructure layer.
 */
public interface BuildSourcePort {
    /**
     * @return the raw console log of the given build.
     */
    String fetchConsoleLog(String jobName, int buildNumber);
}
