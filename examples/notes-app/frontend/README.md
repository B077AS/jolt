# Notes frontend (React + TypeScript + Vite)

The user interface of `notes-app`, written as a normal Vite project. The Java side is in `../src`; see
the [main README](../../../README.md#using-react-typescript-and-vite) for the full walkthrough.

```
npm install
npm run dev        # hot reload; start the Java app with -Dui.url=http://127.0.0.1:5173
npm run build      # typecheck, then dist/index.html (one file); start the Java app with -Dui.file=dist/index.html
```

Opening the dev server in an ordinary browser works for layout, but every request will fail with "not running inside a
Jolt window", because the Java side only exists inside a Jolt window.

Layout: `src/jolt.ts` is the typed bridge, `src/api.ts` the typed calls to the Java handlers, `src/App.tsx` the interface.
