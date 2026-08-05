/**
 * Type declarations for pdfmake's prebuilt bundles.
 *
 * pdfmake publishes `build/pdfmake.js` and `build/vfs_fonts.js` as plain
 * browser scripts with no accompanying .d.ts, so TypeScript refuses to import
 * them under `noImplicitAny` - which is on, and should stay on.
 *
 * WHY NOT JUST TURN THE CHECK OFF
 * Setting `allowJs` or relaxing `noImplicitAny` would silence this and every
 * other missing type in the project at the same time. Declaring the two modules
 * here keeps the strictness everywhere else and makes the gap explicit: these
 * are the only untyped imports, and this file is where to look when pdfmake
 * changes shape.
 *
 * The `any` is deliberate and contained. marks-table.component.ts immediately
 * narrows the import to the small structural type it actually uses, so the
 * looseness does not escape past that one file.
 */
declare module 'pdfmake/build/pdfmake' {
  const pdfMake: any;
  export default pdfMake;
}

declare module 'pdfmake/build/vfs_fonts' {
  const vfs: any;
  export default vfs;
}
