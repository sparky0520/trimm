fetch('https://api.github.com/repos/sparky0520/trimm-releases/releases/latest', {
  headers: { Accept: 'application/vnd.github+json' },
})
  .then((response) => response.ok ? response.json() : Promise.reject(new Error('Release unavailable')))
  .then((release) => {
    const label = document.querySelector('#release-version');
    if (label && release.tag_name) label.textContent = `Version ${release.tag_name.replace(/^v/, '')}`;
  })
  .catch(() => {});
