import { describe, expect, it } from 'vitest';
import { parseJenkinsBuildUrl } from './jenkinsUrl';

describe('parseJenkinsBuildUrl', () => {
  it('parses a top-level job', () => {
    expect(parseJenkinsBuildUrl('https://jenkins.company.com/job/UI-Test/125/')).toEqual({
      jobName: 'UI-Test',
      buildNumber: 125,
      buildUrl: 'https://jenkins.company.com/job/UI-Test/125/',
    });
  });

  it('parses a job in a folder', () => {
    expect(parseJenkinsBuildUrl('https://jenkins.company.com/job/Team/job/UI-Test/125/')).toMatchObject({
      jobName: 'Team/UI-Test',
      buildNumber: 125,
    });
  });

  it('parses any folder depth', () => {
    expect(parseJenkinsBuildUrl('https://jenkins.company.com/job/Team/job/UI/job/Regression/125/')).toMatchObject({
      jobName: 'Team/UI/Regression',
      buildNumber: 125,
      buildUrl: 'https://jenkins.company.com/job/Team/job/UI/job/Regression/125/',
    });
  });

  it('drops trailing parts, query and fragment', () => {
    expect(parseJenkinsBuildUrl('  http://localhost:8080/job/UI-Test/7/console?x=1#footer ')).toEqual({
      jobName: 'UI-Test',
      buildNumber: 7,
      buildUrl: 'http://localhost:8080/job/UI-Test/7/',
    });
  });

  it('supports a Jenkins context path', () => {
    expect(parseJenkinsBuildUrl('https://ci.company.com/jenkins/job/UI-Test/3')).toEqual({
      jobName: 'UI-Test',
      buildNumber: 3,
      buildUrl: 'https://ci.company.com/jenkins/job/UI-Test/3/',
    });
  });

  it('decodes encoded job names', () => {
    expect(parseJenkinsBuildUrl('https://jenkins.company.com/job/My%20Tests/12/')).toMatchObject({
      jobName: 'My Tests',
    });
  });

  it.each([
    ['not a url', 'Geçerli bir URL'],
    ['ftp://jenkins.company.com/job/UI-Test/1/', 'Geçerli bir URL'],
    ['https://github.com/org/repo', 'Jenkins job URL'],
    ['https://jenkins.company.com/job/UI-Test/', 'build numarası'],
    ['https://jenkins.company.com/job/UI-Test/0/', 'build numarası'],
    ['https://jenkins.company.com/job/UI-Test/lastFailedBuild/', 'build numarası'],
    ['https://jenkins.company.com/blue/organizations/jenkins/UI-Test/detail/UI-Test/125/pipeline', 'Jenkins job URL'],
    ['https://jenkins.company.com/job/Repo/job/feature%2Flogin/7/', 'desteklenmiyor'],
  ])('rejects %s', (input, message) => {
    expect(parseJenkinsBuildUrl(input)).toContain(message);
  });
});
