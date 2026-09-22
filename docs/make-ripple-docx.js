const fs = require('fs');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType,
  Table, TableRow, TableCell, WidthType, ShadingType, BorderStyle,
  LevelFormat, PageBreak, TableOfContents, convertInchesToTwip
} = require('docx');

// ---------------------------------------------------------------- palette
const INK = '1A1A1A';
const MUTED = '5F6B7A';
const ACCENT = 'C5211A';   // the same red the plugin uses for "nothing tests this"
const RULE = 'D5DBE1';
const BAND = 'F4F6F8';
const CODEBG = 'F2F3F5';

const LETTER = { width: 12240, height: 15840 };

// ---------------------------------------------------------------- helpers
const H1 = (text) => new Paragraph({
  heading: HeadingLevel.HEADING_1,
  spacing: { before: 420, after: 160 },
  children: [new TextRun({ text, bold: true, size: 32, color: INK, font: 'Calibri' })],
  border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: RULE, space: 8 } }
});

const H2 = (text) => new Paragraph({
  heading: HeadingLevel.HEADING_2,
  spacing: { before: 280, after: 100 },
  children: [new TextRun({ text, bold: true, size: 25, color: INK, font: 'Calibri' })]
});

const H3 = (text) => new Paragraph({
  heading: HeadingLevel.HEADING_3,
  spacing: { before: 200, after: 80 },
  children: [new TextRun({ text, bold: true, size: 22, color: MUTED, font: 'Calibri' })]
});

const P = (text, opts = {}) => new Paragraph({
  spacing: { after: opts.after ?? 120, line: 288 },
  alignment: opts.align,
  children: [new TextRun({
    text, size: opts.size ?? 21, color: opts.color ?? INK,
    italics: opts.italics, bold: opts.bold, font: 'Calibri'
  })]
});

/** Mixed-emphasis paragraph: pass [['plain ',{}],['bold',{bold:true}]] */
const RICH = (runs, opts = {}) => new Paragraph({
  spacing: { after: opts.after ?? 120, line: 288 },
  children: runs.map(([text, o = {}]) => new TextRun({
    text, size: o.size ?? 21, bold: o.bold, italics: o.italics,
    color: o.color ?? INK, font: o.mono ? 'Consolas' : 'Calibri'
  }))
});

const BULLET = (text, level = 0) => new Paragraph({
  numbering: { reference: 'ripple-bullets', level },
  spacing: { after: 70, line: 288 },
  children: [new TextRun({ text, size: 21, color: INK, font: 'Calibri' })]
});

const NUM = (text) => new Paragraph({
  numbering: { reference: 'ripple-numbers', level: 0 },
  spacing: { after: 70, line: 288 },
  children: [new TextRun({ text, size: 21, color: INK, font: 'Calibri' })]
});

const CODE = (lines) => new Paragraph({
  spacing: { before: 80, after: 140 },
  shading: { type: ShadingType.CLEAR, fill: CODEBG },
  border: {
    top: { style: BorderStyle.SINGLE, size: 2, color: RULE, space: 6 },
    bottom: { style: BorderStyle.SINGLE, size: 2, color: RULE, space: 6 },
    left: { style: BorderStyle.SINGLE, size: 12, color: RULE, space: 8 },
    right: { style: BorderStyle.SINGLE, size: 2, color: RULE, space: 6 }
  },
  children: lines.flatMap((l, i) => {
    const run = new TextRun({ text: l, font: 'Consolas', size: 18, color: INK });
    return i === 0 ? [run] : [new TextRun({ break: 1 }), run];
  })
});

/** Callout for the things that matter most. */
const NOTE = (label, text) => new Paragraph({
  spacing: { before: 140, after: 160 },
  shading: { type: ShadingType.CLEAR, fill: BAND },
  border: { left: { style: BorderStyle.SINGLE, size: 18, color: ACCENT, space: 10 } },
  children: [
    new TextRun({ text: label + '  ', bold: true, size: 20, color: ACCENT, font: 'Calibri' }),
    new TextRun({ text, size: 20, color: INK, font: 'Calibri' })
  ]
});

const TOTAL_W = 9360; // 6.5in of usable width on Letter with 1in margins

function table(headers, rows, widths) {
  const cw = widths || headers.map(() => Math.floor(TOTAL_W / headers.length));
  const cell = (text, { bold, fill, color, mono } = {}) => new TableCell({
    width: { size: 0, type: WidthType.DXA },  // overwritten below
    shading: fill ? { type: ShadingType.CLEAR, fill } : undefined,
    margins: { top: 90, bottom: 90, left: 120, right: 120 },
    children: [new Paragraph({
      spacing: { after: 0, line: 264 },
      children: [new TextRun({
        text, bold, size: 19, color: color ?? INK,
        font: mono ? 'Consolas' : 'Calibri'
      })]
    })]
  });

  const mk = (vals, opts) => new TableRow({
    tableHeader: opts.header,
    children: vals.map((v, i) => {
      const c = cell(typeof v === 'object' ? v.text : v, {
        bold: opts.header || (typeof v === 'object' && v.bold),
        fill: opts.header ? BAND : undefined,
        color: typeof v === 'object' ? v.color : undefined,
        mono: typeof v === 'object' ? v.mono : false
      });
      c.options.width = { size: cw[i], type: WidthType.DXA };
      // docx-js reads width off the constructed cell; set both places
      return new TableCell({
        width: { size: cw[i], type: WidthType.DXA },
        shading: opts.header ? { type: ShadingType.CLEAR, fill: BAND } : undefined,
        margins: { top: 90, bottom: 90, left: 120, right: 120 },
        children: c.options.children
      });
    })
  });

  return new Table({
    width: { size: TOTAL_W, type: WidthType.DXA },
    columnWidths: cw,
    borders: {
      top: { style: BorderStyle.SINGLE, size: 4, color: RULE },
      bottom: { style: BorderStyle.SINGLE, size: 4, color: RULE },
      left: { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' },
      right: { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' },
      insideHorizontal: { style: BorderStyle.SINGLE, size: 2, color: RULE },
      insideVertical: { style: BorderStyle.NONE, size: 0, color: 'FFFFFF' }
    },
    rows: [mk(headers, { header: true }), ...rows.map(r => mk(r, {}))]
  });
}

// ---------------------------------------------------------------- content
const children = [];

// Cover
children.push(new Paragraph({ spacing: { before: 1800, after: 0 }, children: [
  new TextRun({ text: 'Ripple', bold: true, size: 80, color: INK, font: 'Calibri' })
]}));
children.push(new Paragraph({ spacing: { after: 40 }, children: [
  new TextRun({ text: 'See what your change can break — and what nothing is testing.',
    size: 26, color: ACCENT, font: 'Calibri' })
]}));
children.push(new Paragraph({
  spacing: { after: 300 },
  border: { bottom: { style: BorderStyle.SINGLE, size: 8, color: RULE, space: 10 } },
  children: [new TextRun({ text: '', size: 2 })]
}));
children.push(P('An IntelliJ IDEA plugin that combines static call-graph analysis with live '
  + 'runtime recording, then uses both together to write the tests you are missing.',
  { size: 23, color: MUTED }));
children.push(P('JetBrains × 42 Abu Dhabi Hackathon — “Help the Developer”', { color: MUTED, size: 20, after: 40 }));
children.push(P('Built for IntelliJ IDEA Community 2024.3.5 · Kotlin · JDK 21', { color: MUTED, size: 20 }));

children.push(new Paragraph({ children: [new PageBreak()] }));

// TOC
children.push(H1('Contents'));
children.push(new TableOfContents('Contents', { hyperlink: true, headingStyleRange: '1-2' }));
children.push(new Paragraph({ children: [new PageBreak()] }));

// 1
children.push(H1('1. The problem Ripple solves'));
children.push(P('You change one method. Four lines. Now answer this: did you just break anything?'));
children.push(P('You do not know. That method might be called from three places or thirty, in files '
  + 'you have never opened. Some of those places have tests protecting them. Some have nothing. '
  + 'You cannot tell by looking, and nothing in a normal IDE tells you.'));
children.push(P('So developers pick one of two bad options:'));
children.push(BULLET('Run the whole test suite every time. On a real project that is minutes, every time you touch a line. Nobody does this.'));
children.push(BULLET('Commit it and hope. This is what people actually do, and it is why bugs ship.'));
children.push(P('The problem has become worse, not better. When an AI agent makes a change it edits '
  + 'several files across modules you have never read. You skim the diff, it looks plausible, you '
  + 'accept it. Nobody in the room knows what that just put at risk.'));
children.push(NOTE('In one sentence',
  'You change some code. Ripple shows you everything that could break, tells you which of it has '
  + 'no test protecting it, records what actually happened when it ran, and then writes the missing tests using the values it observed.'));

// 2
children.push(H1('2. What Ripple does'));
children.push(P('Ripple answers four questions in one keystroke. Each answer is used by the next.'));

children.push(H2('2.1 What did I just put at risk?'));
children.push(P('Ripple takes the methods you have edited and traces outward through the call graph — '
  + 'who calls this, who calls them — up to three hops. That expanding set is the blast radius.'));
children.push(P('This is not a text search. It uses the same PSI index that powers IntelliJ’s own '
  + 'Find Usages and refactoring engine, so the answer is exact rather than probable.'));

children.push(H2('2.2 Which of it is actually protected?'));
children.push(P('Every method in the radius is then classified by whether a test can reach it, '
  + 'computed as reverse reachability across the same call graph: if any test method reaches a node, '
  + 'it is covered; otherwise it is not.'));
children.push(P('Methods nothing tests are shown in red. That red list is the point of the product. '
  + 'It is not "your tests are slow" — it is "here is a hole in your project you did not know about".'));

children.push(H2('2.3 What actually happened when it ran?'));
children.push(P('Ripple then launches your program under the Java Debug Interface — the same '
  + 'protocol IntelliJ’s debugger uses — and records every visit to every line of every method in '
  + 'the blast radius, capturing the value of every local variable at each step.'));
children.push(P('The recorded values are painted into the editor as inline chips at the end of each '
  + 'line, with the variables that change listed first. No breakpoints. No stepping. No debugger UI.'));

children.push(H2('2.4 Write the tests that are missing'));
children.push(P('Finally, Ripple takes the red list and writes a real JUnit test for each method, '
  + 'grounded in four things a generic prompt does not have: the method’s real source, its distance '
  + 'and route from your change, the values observed at runtime, and the test conventions already '
  + 'used in your repository.'));
children.push(NOTE('Why the halves need each other',
  'The red list knows WHICH methods need a test but has no data to write one with. The recording '
  + 'has the data but does not know which tests are missing. Neither half can do this alone — which '
  + 'is the reason they are one product rather than two features.'));

children.push(new Paragraph({ children: [new PageBreak()] }));

// 3
children.push(H1('3. Feature reference'));
children.push(P('Everything Ripple exposes, and the keystroke for it.'));
children.push(table(
  ['Feature', 'Shortcut', 'What it does'],
  [
    [{ text: 'Analyze This Change', bold: true }, { text: 'Ctrl+Alt+R', mono: true },
      'Blast radius, red list, recording and correlation in one pass.'],
    [{ text: 'Write the Missing Tests', bold: true }, { text: 'Ctrl+Alt+W', mono: true },
      'Generates a JUnit test for every untested method, using observed values.'],
    [{ text: 'Rewrite the Generated Tests', bold: true }, { text: 'menu', mono: true },
      'Regenerates tests already written this session.'],
    [{ text: 'Scrub Recording', bold: true }, { text: 'Ctrl+Alt+K', mono: true },
      'Drag through the recording and watch the inline values change.'],
    [{ text: 'Trace This Method', bold: true }, { text: 'Ctrl+Alt+G', mono: true },
      'Records a single method without running the full analysis.'],
    [{ text: 'Edge-case inspection', bold: true }, { text: 'Alt+Enter', mono: true },
      'Flags off-by-one loop bounds and unguarded recursion, with a quick fix.'],
    [{ text: 'Call-graph gutter lens', bold: true }, { text: 'gutter icon', mono: true },
      'Shows callers and callees of any method, navigable on double-click.']
  ],
  [2600, 1700, 5060]
));

children.push(H2('3.1 The analysis panel'));
children.push(P('The Ripple tool window leads with a single number: how many methods in the blast '
  + 'radius have nothing testing them. Everything else is context for that number.'));
children.push(CODE([
  '4                of 6 in the blast radius · 66% uncovered · 3 never ran',
  'WITH NO TEST',
  '',
  'These 6 methods break if PriceCalculator.applyDiscount is wrong.',
  'Red = nothing tests it.  "Never ran" = it did not execute in this run either.',
  '',
  '  PriceCalculator.applyDiscount          you changed this',
  '    Receipt.render          never ran    1 hop',
  '    Report.summarise        never ran    1 hop',
  '      Report.monthlyTotal   never ran    2 hops via Report.summarise',
  '    Cart.checkoutTotal                   1 hop   covered by CartTest',
  '    Invoice.amountDue                    1 hop   covered by InvoiceTest'
]));
children.push(P('Each row states its route, not just its distance: Report.monthlyTotal is at risk '
  + 'because it calls Report.summarise, which calls the method you changed. Tests are folded into '
  + 'the method they cover rather than listed as separate rows — a test is evidence, not a thing '
  + 'that breaks.'));

children.push(H2('3.2 The strongest finding'));
children.push(RICH([
  ['A method that is in the blast radius, has ', {}],
  ['no test', { bold: true }],
  [', and ', {}],
  ['never executed', { bold: true }],
  [' during the recording carries a "never ran" badge. That combination means you have ', {}],
  ['zero evidence', { bold: true }],
  [' it works — not from tests, not from runtime. It is the sharpest thing Ripple can tell you.', {}]
]));

children.push(H2('3.3 The scrubber'));
children.push(P('Once a recording exists you can drag a slider through it and watch the inline '
  + 'values change, pass by pass, like scrubbing a video. Arrow keys step; Escape closes.'));
children.push(P('On the sample project the trail makes a bug visible without any debugging at all:'));
children.push(CODE([
  'total = 0.0  →  100.0  →  90.0     ← discount applied (1st time)',
  '             →  140.0  →  126.0    ← applied AGAIN',
  '             →  151.0  →  135.9    ← and AGAIN',
  '',
  'charged:  135.90        expected: 157.50'
]));
children.push(P('A 10% discount quietly taking 22%. No exception is thrown, so only the value trail '
  + 'reveals it — and the existing test still passes, because its assertion only checks the total '
  + 'went down.'));

children.push(new Paragraph({ children: [new PageBreak()] }));

// 4
children.push(H1('4. How it works'));

children.push(H2('4.1 Pipeline'));
children.push(CODE([
  'Ctrl+Alt+R',
  '    │',
  '    ├─ ProjectRebuilder    compile, so the trace matches the code on screen',
  '    ├─ StalenessCheck      refuse to trace bytecode older than its source',
  '    ├─ ChangeDetector      uncommitted changes → the methods you edited',
  '    ├─ BlastRadiusEngine   transitive callers, ≤3 hops, via ReferencesSearch',
  '    ├─ TestIndex           reverse reachability → the red list',
  '    ├─ BlastTraceSession   JDI recording, scoped to the radius',
  '    └─ BlastExecutionCorrelator   which nodes actually ran',
  '                │',
  '                ├─ BlastPanel            the tree and the numbers',
  '                ├─ TraceTrailRenderer    inline value chips',
  '                └─ TestGenerator         the missing tests'
]));

children.push(H2('4.2 The integration that makes it viable'));
children.push(P('Recording every line of a whole program under JDI is far too slow to be usable. '
  + 'Ripple does not do that. The blast radius decides which methods are worth instrumenting, so '
  + 'the tracer only ever attaches to code your change can actually reach.'));
children.push(P('The relationship runs both ways. The radius makes the recording fast enough to '
  + 'exist; the recording turns the radius from a static guess into observed fact.'));

children.push(H2('4.3 IntelliJ Platform components used'));
children.push(table(
  ['Component', 'Where', 'Why'],
  [
    [{ text: 'PSI + ReferencesSearch', mono: true }, 'blast/', 'Exact call-graph resolution, not text search'],
    [{ text: 'ProjectFileIndex', mono: true }, 'blast/TestIndex', 'Real test-source roots from the module model'],
    [{ text: 'com.sun.jdi', mono: true }, 'engine/', 'Drives the traced JVM directly, no debugger UI'],
    [{ text: 'InlayModel', mono: true }, 'inlay/', 'Value chips rendered into the editor'],
    [{ text: 'ToolWindowFactory', mono: true }, 'blast/ui/', 'The analysis panel'],
    [{ text: 'LocalInspectionTool', mono: true }, 'inspection/', 'Edge-case warnings with quick fixes'],
    [{ text: 'LineMarkerProvider', mono: true }, 'gutter/', 'Call-graph gutter icons'],
    [{ text: 'ChangeListManager', mono: true }, 'blast/ChangeDetector', 'What you have actually edited'],
    [{ text: 'CompilerManager', mono: true }, 'engine/ProjectRebuilder', 'Build before tracing'],
    [{ text: 'PasswordSafe', mono: true }, 'ai/RippleSecrets', 'API key in the OS credential store']
  ],
  [2900, 2400, 4060]
));

children.push(H2('4.4 Safety limits'));
children.push(P('An unbounded graph traversal freezes the IDE. These are enforced, not advisory:'));
children.push(BULLET('Maximum 3 call hops from the changed method'));
children.push(BULLET('Maximum 500 nodes per scan, and 200 references per symbol'));
children.push(BULLET('Recording capped by event count and wall-clock time; the debuggee is killed on timeout'));
children.push(BULLET('All PSI work in short read actions; nothing long-running on the UI thread'));
children.push(P('When a limit is hit the panel says so. Silent truncation would read as a wrong '
  + 'answer rather than a partial one.'));

children.push(new Paragraph({ children: [new PageBreak()] }));

// 5
children.push(H1('5. The AI layer'));
children.push(P('Ripple uses a language model for exactly one job: writing the tests the red list '
  + 'says are missing. Everything else — the call graph, the coverage arithmetic, the recording — '
  + 'is deterministic and would give the same answer with the network unplugged.'));

children.push(H2('5.1 What makes it more than a prompt around a function body'));
children.push(P('The model receives four pieces of grounding:'));
children.push(NUM('The method’s real source, read from PSI.'));
children.push(NUM('Why it matters — its hop distance and route from the method you just changed. Real codebase context, not a selected snippet.'));
children.push(NUM('The values observed at runtime. A generic prompt invents new double[]{1, 2, 3}; Ripple passes the arguments that genuinely flowed through the method.'));
children.push(NUM('The repository’s existing test conventions, read from a real test file, so the output matches the project rather than the model’s defaults.'));
children.push(P('When a method never executed, Ripple borrows real values from a method it calls '
  + 'that did — so the generated test still uses data the program actually saw, rather than '
  + 'inventing inputs or writing an assertion that recomputes its own expected value.'));

children.push(H2('5.2 Degradation'));
children.push(P('No API key, no network, or a rejected request all fall back to a deterministic '
  + 'template that carries the same observed values as comments. A machine with no internet still '
  + 'produces test files, and the notification says which path was taken.'));

children.push(H2('5.3 Idempotence'));
children.push(P('Tests are written once. Running the action again reports what it skipped rather '
  + 'than silently regenerating files that already exist; an explicit "Rewrite" action exists for '
  + 'when you do want a fresh attempt.'));

children.push(H2('5.4 Key handling'));
children.push(P('The API key is never stored in source. Ripple reads it from IntelliJ’s PasswordSafe, '
  + 'then environment variables, then a git-ignored local properties file.'));

// 6
children.push(H1('6. Why this benefits developers'));

children.push(H2('6.1 It answers a question nothing else answers'));
children.push(P('Coverage tools tell you what is untested across the whole project — a number so '
  + 'large it is ignored. Find Usages tells you who calls a symbol you picked. Neither tells you '
  + 'what the change sitting in your working copy right now has put at risk.'));

children.push(H2('6.2 It turns a report into a fix'));
children.push(P('Knowing four methods are untested is a chore. Ripple closes the loop: it finds the '
  + 'hole, and then fills it with a test written from data it observed. Problem, evidence and fix in '
  + 'one keystroke.'));

children.push(H2('6.3 It makes runtime state visible where you are already looking'));
children.push(P('Debugging normally means breakpoints, stepping, and holding the mapping between '
  + 'code and state in your head while looking at a separate panel. Ripple puts the values on the '
  + 'lines they belong to, so the code and its behaviour are in one place.'));

children.push(H2('6.4 It is honest about what it knows'));
children.push(P('Ripple never claims more than it can support:'));
children.push(BULLET('It only says a method "never ran" if it actually instrumented that method.'));
children.push(BULLET('If the recording times out, the judgement is withheld rather than guessed.'));
children.push(BULLET('It refuses to trace bytecode older than its source, instead of reporting values from code you already changed.'));
children.push(BULLET('Coverage is static reachability, and the UI says so.'));
children.push(P('That matters because a tool that is confidently wrong once is never trusted again.'));

children.push(H2('6.5 It works on code you wrote a minute ago'));
children.push(P('Write a new method, press one key. Ripple compiles the project first — and if the '
  + 'IDE has nothing to build, compiles the sources itself with debug information — so there is no '
  + 'ritual to remember before the tool is useful.'));

// 7
children.push(H1('7. Scope and limitations'));
children.push(P('Stated plainly, because knowing where a tool stops is part of trusting it.'));
children.push(table(
  ['Limitation', 'What it means'],
  [
    ['Java only', 'PSI resolution and the tracer are Java-specific. Other JVM languages are out of scope.'],
    ['Static reachability, not measured coverage', 'Coverage is computed from the call graph, so it is instant and needs no coverage run — but it approximates rather than measures.'],
    ['Reflection and dependency injection are invisible', 'Only static references are followed. Runtime wiring would need coverage data from a previous run.'],
    ['Entry point selection is automatic', 'The recording launches a main() method found in the project. There is no run-configuration picker, so a project with several entry points may not record the one you meant.'],
    ['Requires debug information', 'Local variable names come from classes compiled with -g. Without it you get line hits and no values.'],
    ['A focused recording, not a profiler', 'The tracer records the blast radius of one change, not a production trace.'],
    ['Whole-file change granularity', 'Every method in an edited file is treated as changed. Line-level mapping was deliberately not attempted.']
  ],
  [3100, 6260]
));

// 8
children.push(H1('8. Engineering quality'));
children.push(P('Ripple is built to be looked at, not just demonstrated.'));
children.push(table(
  ['', ''],
  [
    [{ text: 'Source', bold: true }, '41 Kotlin files across analysis, recording, UI and AI layers'],
    [{ text: 'Tests', bold: true }, '21 automated tests, all passing — including end-to-end tests that launch a real JVM and assert on captured values'],
    [{ text: 'History', bold: true }, '33 commits, each explaining the defect it fixes'],
    [{ text: 'Dependencies', bold: true }, 'None beyond the IntelliJ Platform and the JDK. HTTP and JSON come from java.net.http and the Gson the IDE already bundles'],
    [{ text: 'Offline', bold: true }, 'Every feature except test generation works with no network at all'],
    [{ text: 'Threading', bold: true }, 'All PSI in read actions, all long work off the UI thread, cancellation honoured throughout']
  ],
  [2200, 7160]
));
children.push(P('The test suite exists because of a specific failure. An early version of the tracer '
  + 'captured nothing roughly five runs in six, caused by a double resume in the JDI event loop — a '
  + 'bug that compiles cleanly, passes every static check, and looks exactly like an unreliable '
  + 'machine. One of the tests now runs the recorder five consecutive times and asserts identical '
  + 'non-zero results, which is the only thing that would have caught it.'));

children.push(NOTE('The principle throughout',
  'Wrong data that looks right costs far more than no data. Where Ripple cannot be sure, it says '
  + 'nothing rather than guessing — and where it can be sure, it shows you exactly what it saw.'));

// ---------------------------------------------------------------- document
const doc = new Document({
  creator: 'Team Ripple',
  title: 'Ripple — IntelliJ IDEA Plugin',
  description: 'What Ripple does, how it works, and why it helps developers',
  numbering: {
    config: [
      {
        reference: 'ripple-bullets',
        levels: [
          { level: 0, format: LevelFormat.BULLET, text: '\u2022', alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: convertInchesToTwip(0.3), hanging: convertInchesToTwip(0.18) } } } },
          { level: 1, format: LevelFormat.BULLET, text: '\u25E6', alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: convertInchesToTwip(0.6), hanging: convertInchesToTwip(0.18) } } } }
        ]
      },
      {
        reference: 'ripple-numbers',
        levels: [
          { level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT,
            style: { paragraph: { indent: { left: convertInchesToTwip(0.35), hanging: convertInchesToTwip(0.22) } } } }
        ]
      }
    ]
  },
  sections: [{
    properties: {
      page: {
        size: LETTER,
        margin: { top: 1440, right: 1440, bottom: 1440, left: 1440 }
      }
    },
    children
  }]
});

Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync('C:/Users/User/Desktop/Ripple/Ripple.docx', buf);
  console.log('written: Ripple.docx  (' + Math.round(buf.length / 1024) + ' KB)');
});
