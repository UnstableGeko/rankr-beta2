async function performSearch(query) {
    const titleEl = document.getElementById('search-title');
    const resultsEl = document.getElementById('search-results');
    if (!resultsEl) return;

    if (!query) {
        if (titleEl) titleEl.textContent = 'Search for a game above';
        return;
    }

    if (titleEl) titleEl.textContent = `Results for "${query}"`;
    resultsEl.innerHTML = '<p class="search-empty">Loading&hellip;</p>';

    try {
        const response = await fetch(`/api/search?q=${encodeURIComponent(query)}`, {
            method: 'POST'
        });
        const games = await response.json();

        if (!Array.isArray(games) || games.length === 0) {
            resultsEl.innerHTML = '<p class="search-empty">No results found.</p>';
            return;
        }

        resultsEl.innerHTML = '';
        games.forEach(game => {
            const coverUrl = game.cover
                ? `https://images.igdb.com/igdb/image/upload/t_cover_big/${game.cover.image_id}.jpg`
                : null;
            const score = game.total_rating ?? game.rating ?? null;
            const fill = score ? Math.min(5, Math.round(score / 20)) : 0;
            const year = game.first_release_date
                ? new Date(game.first_release_date * 1000).getFullYear()
                : '';
            const genre = game.genres?.[0]?.name || '';

            const row = document.createElement('a');
            row.href = `/games/${game.slug}`;
            row.className = 'search-row';
            row.innerHTML = `
                <div class="search-row-cover">
                    ${coverUrl ? `<img src="${coverUrl}" alt="${game.name}">` : ''}
                </div>
                <div>
                    <div class="search-row-title">${game.name}</div>
                    <div class="search-row-sub">${[genre, year].filter(Boolean).join(' · ')}</div>
                </div>
                ${score ? `
                <div class="search-row-rating">
                    <div class="bar-rating" data-fill="${fill}">
                        <span></span><span></span><span></span><span></span><span></span>
                    </div>
                    <span class="search-row-score">${(score / 20).toFixed(1)}</span>
                </div>` : ''}
            `;
            resultsEl.appendChild(row);
        });
    } catch (error) {
        console.error('Search error:', error);
        resultsEl.innerHTML = '<p class="search-empty">Error loading results.</p>';
    }
}

window.addEventListener('DOMContentLoaded', () => {
    const params = new URLSearchParams(window.location.search);
    const query = params.get('q');

    const heroInput = document.getElementById('search-hero-input');
    const heroBtn = document.getElementById('search-hero-btn');

    if (heroInput) {
        if (query) heroInput.value = query;

        const doSearch = () => {
            const q = heroInput.value.trim();
            if (q) window.location.href = `/search.html?q=${encodeURIComponent(q)}`;
        };

        heroInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') doSearch();
        });

        if (heroBtn) heroBtn.addEventListener('click', doSearch);
    }

    performSearch(query);
});
