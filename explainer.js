const details = {
  coverage: {
    tag: 'Representative coverage',
    title: 'Enough context to preserve the story',
    body: 'ctrlB includes moments across the whole recording, not just suspected mistakes. This helps the agent understand what should stay.',
  },
  candidate: {
    tag: 'Measured candidate',
    title: 'A moment worth closer inspection',
    body: 'A local measurement found a pause, repeated passage, technical change or other candidate. The agent decides what it means in context.',
  },
  uncertain: {
    tag: 'Uncertainty range',
    title: 'Do not hide weak local evidence',
    body: 'When Layer 1 is unsure, it includes the range instead of silently filtering it out. Layer 2 can inspect it directly or request more precision.',
  },
  protected: {
    tag: 'User constraint',
    title: 'Keep the pricing explanation',
    body: 'Explicit instructions and previously accepted choices remain attached to the run. Proposed cuts cannot silently remove this protected section.',
  },
};

const card = document.querySelector('#timeline-detail');
document.querySelectorAll('.segment').forEach((button) => {
  button.addEventListener('click', () => {
    document.querySelectorAll('.segment').forEach((item) => item.classList.remove('active'));
    button.classList.add('active');
    const detail = details[button.dataset.detail];
    card.querySelector('.detail-tag').textContent = detail.tag;
    card.querySelector('h3').textContent = detail.title;
    card.querySelector('p').textContent = detail.body;
  });
});
