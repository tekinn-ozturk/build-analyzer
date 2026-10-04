export interface JenkinsBuild {
  /** Jenkins full name, folders joined with "/": /job/Team/job/UI-Test/125/ → "Team/UI-Test". */
  jobName: string;
  buildNumber: number;
  /** Normalised build page, without trailing parts such as /console. */
  buildUrl: string;
}

/**
 * Parses a Jenkins build URL: .../job/<name>[/job/<name>...]/<number>/[anything].
 * Returns the build, or a Turkish error message for the form.
 * Blue Ocean, lastFailedBuild and other special URLs are not supported (V1).
 */
export function parseJenkinsBuildUrl(input: string): JenkinsBuild | string {
  let url: URL;
  try {
    url = new URL(input.trim());
  } catch {
    return 'Geçerli bir URL değil.';
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return 'Geçerli bir URL değil.';
  }

  const segments = url.pathname.split('/').filter(Boolean);
  let i = segments.indexOf('job'); // Jenkins may run under a context path, e.g. /jenkins/job/...
  if (i === -1) {
    return 'Bu bir Jenkins job URL\'i değil. Örnek: https://jenkins.company.com/job/UI-Test/125/';
  }

  const names: string[] = [];
  while (segments[i] === 'job' && i + 1 < segments.length) {
    let name: string;
    try {
      name = decodeURIComponent(segments[i + 1]);
    } catch {
      return 'URL\'deki job adı çözümlenemedi.';
    }
    if (name.includes('/')) {
      return 'Adında "/" olan job\'lar (ör. multibranch dalları) desteklenmiyor.';
    }
    names.push(name);
    i += 2;
  }

  const buildNumber = segments[i];
  if (names.length === 0 || buildNumber === undefined || !/^[1-9]\d*$/.test(buildNumber)) {
    return 'URL bir build numarası içermiyor. Örnek: .../job/UI-Test/125/';
  }

  return {
    jobName: names.join('/'),
    buildNumber: Number(buildNumber),
    buildUrl: `${url.origin}/${segments.slice(0, i + 1).join('/')}/`,
  };
}
