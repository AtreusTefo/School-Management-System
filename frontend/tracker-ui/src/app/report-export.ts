import DataTable from 'datatables.net-dt';

/**
 * pdfmake, loaded ONLY when somebody actually exports a table to PDF.
 *
 * SHARED BY TWO TABLES, WHICH IS WHY THIS IS ITS OWN FILE
 * Both the marks table and the performance-summary table offer Export PDF,
 * and both need the exact same lazy-loading dance. Keeping one copy here
 * means the two components ask the SAME cached promise - a reader on the
 * Reports page who exports one table and then the other only ever downloads
 * pdfmake once, not twice - and a future third exportable table gets PDF
 * support by importing this function rather than re-deriving it.
 *
 * WHY THIS IS NOT A NORMAL IMPORT
 * pdfmake plus its embedded Roboto fonts is about 1.9 MB - more than four
 * times the rest of this application put together. Importing it at the top
 * of a file puts all of it in the initial bundle, so every student who signs
 * in to look at their marks downloads a PDF generator they may never use, on
 * whatever connection they have.
 *
 * A dynamic import() makes it a separate chunk fetched on first use and
 * cached thereafter.
 */
let pdfMakeReady: Promise<unknown> | null = null;

export function loadPdfMake(): Promise<unknown> {
  if (pdfMakeReady) {
    return pdfMakeReady;
  }

  pdfMakeReady = Promise.all([
    import('pdfmake/build/pdfmake'),
    import('pdfmake/build/vfs_fonts')
  ]).then(([pdfMakeModule, vfsModule]) => {
    const pdfMake = (pdfMakeModule as any).default ?? pdfMakeModule;
    const vfs = (vfsModule as any).default ?? vfsModule;

    /*
     * pdfmake 0.3 replaced "assign to .vfs" with addVirtualFileSystem(). Both
     * are handled, so an upgrade that moves it again degrades to a PDF
     * button that reports a failure rather than a page that will not load.
     */
    if (typeof pdfMake.addVirtualFileSystem === 'function') {
      pdfMake.addVirtualFileSystem(vfs);
    } else {
      pdfMake.vfs = vfs;
    }

    // DataTables Buttons has to be handed the instance explicitly; it does
    // not look for a global.
    const dt = DataTable as unknown as {
      Buttons?: { pdfMake?: (instance: unknown) => void };
    };
    dt.Buttons?.pdfMake?.(pdfMake);

    return pdfMake;
  });

  return pdfMakeReady;
}

/**
 * A DataTables custom-button `action` that awaits loadPdfMake() before
 * handing off to the library's own built-in `pdfHtml5` action.
 *
 * Both marks-table.component.ts and performance-table.component.ts need
 * this exact sequence - show "Preparing...", await the chunk, run the real
 * export, restore the label, report a failure if the chunk could not be
 * fetched - so it is written once here rather than once per table.
 *
 * WHY THE RETURNED FUNCTION IS NOT AN ARROW FUNCTION
 * DataTables invokes a button's `action` as `action.call(buttonApi, e, dt,
 * node, config)`, handing it a meaningful `this`. An arrow function would
 * ignore that entirely and close over whatever `this` happened to be in
 * scope where exportPdfAction() was CALLED - in the original single-file
 * version that was the component instance, purely by accident of where the
 * config object literal was built. Declaring this as a plain `function`
 * instead means `invokedAs` is genuinely whatever DataTables supplied, and
 * `pdfAction.call(invokedAs, ...)` hands the library's own built-in action
 * the context it actually expects, rather than an unrelated object it
 * happened to tolerate.
 */
export function exportPdfAction(
  reportTitle: () => string,
  onFailure: (message: string) => void
) {
  return function (this: unknown, e: any, dt: any, node: any, config: any) {
    const invokedAs = this;
    const button = node?.[0] ?? node;
    const original = button?.textContent;
    if (button) {
      button.textContent = 'Preparing...';
    }

    loadPdfMake().then(() => {
      const pdfAction = (DataTable as any).ext?.buttons?.pdfHtml5?.action;
      if (typeof pdfAction !== 'function') {
        throw new Error('The PDF export is unavailable in this build.');
      }
      pdfAction.call(invokedAs, e, dt, node, {
        ...config,
        extend: 'pdfHtml5',
        orientation: 'landscape',
        pageSize: 'A4',
        title: reportTitle(),
        // The Actions column, where present, holds buttons - which mean
        // nothing on paper.
        exportOptions: { columns: ':not(.col-actions)' }
      });
    }).catch((error: unknown) => {
      // Every failure must reach the user. A button that silently does
      // nothing is worse than one that says why.
      onFailure(error instanceof Error ? error.message : 'The PDF export could not be loaded.');
    }).finally(() => {
      if (button && original !== undefined) {
        button.textContent = original;
      }
    });
  };
}
