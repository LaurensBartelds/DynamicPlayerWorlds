// The root build applies no plugins and declares no dependencies. Shared setup
// lives in build-logic/ as convention plugins, so :core (a plain library) and
// the two plugin modules can diverge cleanly rather than sharing one
// allprojects block that has to accommodate both.
