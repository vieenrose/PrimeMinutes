/* Token-level Levenshtein alignment counts for long-form WER/CER (score.py).
 * Usage: editdist ref.txt hyp.txt  (one integer token id per line) -> "N S D I" on stdout.
 * Two-row DP carrying (cost, S, D, I); ties prefer substitution, then deletion. */
#include <stdio.h>
#include <stdlib.h>
typedef struct { int c, s, d, i; } Cell;
static int *load(const char *p, int *n) {
    FILE *f = fopen(p, "r"); if (!f) { perror(p); exit(1); }
    int cap = 1 << 16, *v = malloc(cap * sizeof(int)); *n = 0;
    while (fscanf(f, "%d", &v[*n]) == 1) { if (++*n == cap) v = realloc(v, (cap *= 2) * sizeof(int)); }
    fclose(f); return v;
}
int main(int argc, char **argv) {
    if (argc != 3) { fprintf(stderr, "usage: editdist ref hyp\n"); return 2; }
    int n, m; int *r = load(argv[1], &n), *h = load(argv[2], &m);
    Cell *a = malloc((m + 1) * sizeof(Cell)), *b = malloc((m + 1) * sizeof(Cell));
    for (int j = 0; j <= m; j++) a[j] = (Cell){ j, 0, 0, j };
    for (int i = 1; i <= n; i++) {
        b[0] = (Cell){ i, 0, i, 0 };
        for (int j = 1; j <= m; j++) {
            Cell sub = a[j - 1]; if (r[i - 1] != h[j - 1]) { sub.c++; sub.s++; }
            Cell del = a[j]; del.c++; del.d++;
            Cell ins = b[j - 1]; ins.c++; ins.i++;
            Cell best = sub; if (del.c < best.c) best = del; if (ins.c < best.c) best = ins;
            b[j] = best;
        }
        Cell *t = a; a = b; b = t;
    }
    printf("%d %d %d %d\n", n, a[m].s, a[m].d, a[m].i);
    return 0;
}
