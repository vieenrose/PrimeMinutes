// Locale-independent fixed-point text for numbers that cross a parsed boundary (JNI / C API segment
// encoding, JSON stats). printf's %f follows LC_NUMERIC: glibc writes "0,500" under fr_FR or de_DE
// (seen on the host JVM, which applies the user locale), and the Kotlin/Swift parsers want "0.500".
// Integer formatting only: no decimal point, no grouping, whatever the process locale.
#pragma once

#include <cmath>
#include <cstdio>
#include <string>

namespace nemo {

inline std::string fixed(double v, int decimals) {
    static const long long kP10[] = {1, 10, 100, 1000, 10000, 100000, 1000000};
    if (decimals < 0) decimals = 0;
    if (decimals > 6) decimals = 6;
    if (!std::isfinite(v) || std::fabs(v) > 9e12) v = 0.0;
    const long long scale = kP10[decimals];
    const long long q = std::llround(v * (double)scale);
    const bool neg = q < 0;
    const unsigned long long a = neg ? 0ULL - (unsigned long long)q : (unsigned long long)q;
    char buf[48];
    if (decimals == 0) std::snprintf(buf, sizeof buf, "%s%llu", neg ? "-" : "", a);
    else std::snprintf(buf, sizeof buf, "%s%llu.%0*llu", neg ? "-" : "", a / (unsigned long long)scale, decimals,
                       a % (unsigned long long)scale);
    return buf;
}

}  // namespace nemo
