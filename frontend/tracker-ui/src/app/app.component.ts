import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import {
  Assessment, Assignment, AssignmentService, Course, CurrentUser, Performance,
  SchoolClass, Submission, Subject
} from './assignment.service';
import { FileChosen, SubmissionTableComponent } from './submission-table.component';
import { MarksTableComponent } from './marks-table.component';
import { environment } from '../environments/environment';

/**
 * THE COMPONENT
 * -------------
 * The controller of the UI. It asks the service for data, stores it, and gives
 * the template something to display. It reacts to user actions (clicks).
 *
 * It shows or hides controls by role, but it does NOT enforce anything. Every
 * rule it reflects is also enforced by the server - hiding a button is a
 * courtesy to the user, not a security measure, because anyone can call the API
 * directly.
 *
 * WHAT THE TWO ROLES SEE, AND WHY THEY DIFFER IN SHAPE
 * A STUDENT works on submissions: upload a PDF, hand it in, download it back.
 * A TEACHER works on both - a marking queue of submissions across their courses,
 * and the assignments they have set, which is where creating, editing and
 * deleting live. Those are genuinely different jobs, so they are different
 * panels rather than one table with hidden columns.
 *
 * The submission table itself is not drawn here. SubmissionTableComponent hands
 * the rows to DataTables and lets it own that piece of the page; this component
 * supplies the data and reacts to what the user asked to do with a row. Every
 * call to the API still goes through AssignmentService.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, SubmissionTableComponent, MarksTableComponent],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {

  /** Who is signed in, or null when nobody is. Drives login screen vs list. */
  user: CurrentUser | null = null;

  /** True until the first "who am I?" answer arrives, so we do not flash the login form. */
  checkingSession = true;

  // Sign-in form
  loginUsername = '';
  loginPassword = '';

  /**
   * True while a request is in flight, so the interface can say so.
   *
   * Kept as separate flags rather than one shared "busy", because each blocks a
   * different part of the screen: loadingList replaces the table, signingIn
   * disables the sign-in button, and checkingSession above holds back the whole
   * page. A single flag would make signing in blank out the list, and a slow
   * refresh disable a form the user was not waiting on.
   */
  loadingList = false;
  signingIn = false;
  uploading = false;

  /** Everything the server returned. */
  submissions: Submission[] = [];

  /** The work a teacher has set. Empty for a student, who sets none. */
  assignments: Assignment[] = [];

  /** The courses the caller is involved in - the picker when setting work. */
  courses: Course[] = [];

  /** Reference data, loaded only for a teacher setting up the timetable. */
  subjects: Subject[] = [];
  classes: SchoolClass[] = [];

  /**
   * The subset handed to the table, after the status filter.
   *
   * Free-text search is NOT applied here - DataTables owns that, and doing it in
   * both places would mean two search boxes fighting over the same rows.
   *
   * Held as a field and reassigned by applyFilters() rather than exposed as a
   * getter. A getter returns a new array on every change-detection pass, which
   * would look like new data to the table component and trigger a needless
   * redraw several times a second.
   */
  visibleSubmissions: Submission[] = [];

  /** Status filter. A client-side view of data already loaded. */
  statusFilter: 'ALL' | 'IN_PROGRESS' | 'SUBMITTED' | 'OVERDUE' = 'ALL';

  // ----- setting work (teachers) ---------------------------------------------

  newTitle = '';
  newDescription = '';
  newDueDate = '';

  /**
   * Which courses the new work is for.
   *
   * A SET rather than a single value, because "assign to more than one class at
   * a time" is the requirement. Each selected course becomes its own assignment
   * with its own register and its own progress.
   */
  selectedCourseIds = new Set<number>();

  showSetWork = false;

  // Editing an assignment
  editingAssignmentId: number | null = null;
  editTitle = '';
  editDescription = '';
  editDueDate = '';

  // ----- timetable admin (teachers) ------------------------------------------

  showTimetable = false;
  newSubjectCode = '';
  newSubjectName = '';
  newClassName = '';
  courseSubjectId: number | null = null;
  courseClassId: number | null = null;
  enrolClassId: number | null = null;
  enrolUsername = '';
  timetableNotice: string | null = null;

  // ----- marks and performance (EPIC-16) -------------------------------------

  /** Every mark the caller may see. A student's report, or a teacher's mark book. */
  marks: Assessment[] = [];

  /** Performance per student per subject, derived by the server from those marks. */
  performance: Performance[] = [];

  showMarkEntry = false;
  markCourseId: number | null = null;
  markStudent = '';
  markName = '';

  /**
   * Held as STRINGS, not numbers, all the way to the server.
   *
   * An <input type="number"> bound to a number goes through a JavaScript double,
   * which is how 34.5 can arrive as 34.499999999999996 at a BigDecimal column.
   * Keeping the typed text intact means the server parses exactly what the
   * teacher entered.
   */
  markScore = '';
  markMaxScore = '';

  /** The register for the class of the selected course, for the student picker. */
  markCandidates: string[] = [];

  editingMarkId: number | null = null;
  markNotice: string | null = null;

  // ----- account self-service (EPIC-09) --------------------------------------

  /** Change-password form. */
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';
  changingPassword = false;
  passwordNotice: string | null = null;

  /**
   * True when the user opened the change-password form themselves, as opposed to
   * being sent there because their account is pending.
   *
   * Kept separate from user.mustChangePassword so the form can be dismissed in
   * the voluntary case and cannot be in the forced one.
   */
  showPasswordForm = false;

  /** Create-account form (teachers only). */
  newUsername = '';
  newUserPassword = '';
  creatingUser = false;
  showCreateUser = false;
  createUserNotice: string | null = null;

  /** The last error to show the user, or null when all is well. */
  errorMessage: string | null = null;

  constructor(private service: AssignmentService) {}

  /**
   * On startup: get a CSRF token, then ask whether we are already signed in.
   *
   * A 401 from /me is the normal "not signed in" answer, not a failure worth
   * showing - so it is swallowed deliberately and only unexpected errors reach
   * the banner.
   */
  ngOnInit(): void {
    this.service.primeCsrf().subscribe({
      next: () => this.checkSession(),
      error: () => this.checkSession()
    });
  }

  private checkSession(): void {
    this.service.me().subscribe({
      next: (user) => {
        this.user = user;
        this.checkingSession = false;
        // An account pending a password change may not read the list - the
        // server answers 403. Asking anyway would put a red banner on top of a
        // screen that is already explaining what to do.
        if (!user.mustChangePassword) {
          this.loadEverything();
        }
      },
      error: () => {
        this.user = null;
        this.checkingSession = false;
      }
    });
  }

  get isTeacher(): boolean {
    return this.user?.role === 'TEACHER';
  }

  // ----- presentation helpers -------------------------------------------------
  // These exist only to keep the template readable. Nothing here decides
  // anything; they turn values the server sent into something displayable.

  /** The letter shown in the avatar circle. */
  get userInitial(): string {
    return this.user ? this.user.username.charAt(0).toUpperCase() : '?';
  }

  get submittedCount(): number {
    return this.submissions.filter(s => s.status === 'SUBMITTED').length;
  }

  get inProgressCount(): number {
    return this.submissions.filter(s => s.status === 'IN_PROGRESS').length;
  }

  get overdueCount(): number {
    return this.submissions.filter(s => s.overdue).length;
  }

  /**
   * The distinct subjects the caller is involved in.
   *
   * For a student this is literally "the subjects I am taught" - one of the
   * requirements, answered from the course list rather than from its own
   * endpoint, because it is the same data.
   */
  get mySubjects(): string[] {
    return [...new Set(this.courses.map(c => c.subjectName))].sort();
  }

  /** For a student, the distinct teachers who teach them. */
  get myTeachers(): string[] {
    return [...new Set(this.courses.map(c => c.teacherUsername))].sort();
  }

  // ----- filtering ------------------------------------------------------------

  setStatusFilter(filter: typeof this.statusFilter): void {
    this.statusFilter = filter;
    this.applyFilters();
  }

  /**
   * Narrow the loaded list down to the chosen status.
   *
   * This filters data already in the browser; it does not ask the server for a
   * different set. That keeps the endpoint's meaning intact - the API still
   * decides what this person is ALLOWED to see, and the filter only decides what
   * they are currently LOOKING at. A filter must never be mistaken for access
   * control.
   *
   * A NEW array is assigned rather than the existing one being mutated, because
   * the table component compares its @Input() by reference: mutating in place
   * would change the data with nothing to tell Angular it had happened.
   */
  private applyFilters(): void {
    this.visibleSubmissions = this.submissions.filter(s =>
      this.statusFilter === 'ALL' ? true :
      this.statusFilter === 'OVERDUE' ? s.overdue :
      s.status === this.statusFilter
    );
  }

  // ----- authentication ------------------------------------------------------

  onLogin(): void {
    const username = this.loginUsername.trim();
    if (!username || !this.loginPassword) {
      return;
    }
    this.signingIn = true;
    this.service.login(username, this.loginPassword).subscribe({
      next: (user) => {
        this.signingIn = false;
        this.user = user;
        this.loginPassword = '';
        this.errorMessage = null;
        this.passwordNotice = null;
        // Same reason as checkSession: a pending account gets the change-password
        // screen, not a list request that is going to be refused.
        if (!user.mustChangePassword) {
          this.loadEverything();
        }
      },
      // Handled separately rather than through showError: a 401 here means the
      // credentials were wrong, NOT that a session expired. Routing it through
      // the generic handler produced "Your session has ended, please sign in
      // again" on the login screen, which is both confusing and untrue.
      error: (err: unknown) => {
        this.signingIn = false;
        const status = (err as { status?: number })?.status;
        this.errorMessage = status === 401
          ? 'Invalid username or password.'
          : null;
        if (this.errorMessage === null) {
          this.showError(err, 'Could not sign in');
        }
      }
    });
  }

  onLogout(): void {
    this.service.logout().subscribe({
      next: () => {
        this.user = null;
        this.submissions = [];
        this.visibleSubmissions = [];
        this.assignments = [];
        this.courses = [];
        this.marks = [];
        this.performance = [];
        this.statusFilter = 'ALL';
        this.errorMessage = null;
        // Clear the account forms too. Leaving a half-typed password in a field
        // for the next person to sign in at this browser would be careless.
        this.showPasswordForm = false;
        this.showCreateUser = false;
        this.showSetWork = false;
        this.showTimetable = false;
        this.showMarkEntry = false;
        this.markNotice = null;
        this.passwordNotice = null;
        this.createUserNotice = null;
        this.timetableNotice = null;
        this.resetPasswordFields();
      },
      error: (err) => this.showError(err, 'Could not sign out')
    });
  }

  // ----- account self-service (EPIC-09) --------------------------------------

  /**
   * True when the account cannot do anything until its password is replaced.
   *
   * The list is not even requested in this state - not to enforce anything, but
   * because the server would refuse it with a 403, and a red banner on a screen
   * that is already telling the user what to do would only be noise.
   */
  get mustChangePassword(): boolean {
    return this.user?.mustChangePassword === true;
  }

  /** Whether to render the change-password form at all. */
  get passwordFormVisible(): boolean {
    return this.mustChangePassword || this.showPasswordForm;
  }

  openPasswordForm(): void {
    this.showPasswordForm = true;
    this.passwordNotice = null;
    this.resetPasswordFields();
  }

  /** Only reachable when the change is voluntary; a forced one has no way out. */
  closePasswordForm(): void {
    this.showPasswordForm = false;
    this.resetPasswordFields();
  }

  private resetPasswordFields(): void {
    this.currentPassword = '';
    this.newPassword = '';
    this.confirmPassword = '';
  }

  /**
   * Client-side check that the two new-password fields agree.
   *
   * The server never sees `confirmPassword` and has no opinion on it - it is a
   * typing check, not a rule. Everything the server does enforce (length, that
   * the current password matches, that the new one differs) is checked there and
   * surfaces as a normal error.
   */
  get passwordsMatch(): boolean {
    return this.newPassword === this.confirmPassword;
  }

  get canSubmitPassword(): boolean {
    return !this.changingPassword
        && this.currentPassword.length > 0
        && this.newPassword.length >= 8
        && this.passwordsMatch;
  }

  onChangePassword(): void {
    if (!this.canSubmitPassword) {
      return;
    }
    this.changingPassword = true;
    this.service.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: (user) => {
        this.changingPassword = false;
        const wasForced = this.mustChangePassword;
        // Take the server's word for the new state rather than assuming the flag
        // cleared - it is the server that decides when the account is usable.
        this.user = user;
        this.showPasswordForm = false;
        this.resetPasswordFields();
        this.errorMessage = null;
        this.passwordNotice = 'Your password has been changed.';
        // Coming out of the forced state, nothing has ever been loaded.
        if (wasForced) {
          this.loadEverything();
        }
      },
      error: (err) => {
        this.changingPassword = false;
        this.showError(err, 'Could not change your password');
      }
    });
  }

  // ----- creating an account (teachers only) ---------------------------------

  openCreateUser(): void {
    this.showCreateUser = true;
    this.createUserNotice = null;
    this.newUsername = '';
    this.newUserPassword = '';
  }

  closeCreateUser(): void {
    this.showCreateUser = false;
  }

  get canCreateUser(): boolean {
    return !this.creatingUser
        && this.newUsername.trim().length > 0
        && this.newUserPassword.length >= 8;
  }

  onCreateUser(): void {
    if (!this.canCreateUser) {
      return;
    }
    this.creatingUser = true;
    const username = this.newUsername.trim();
    this.service.createStudent(username, this.newUserPassword).subscribe({
      next: () => {
        this.creatingUser = false;
        this.createUserNotice =
          `Account "${username}" created. They must change this password when they first sign in. `
          + `Enrol them in a class so they receive work.`;
        this.newUsername = '';
        this.newUserPassword = '';
        this.errorMessage = null;
      },
      error: (err) => {
        this.creatingUser = false;
        this.showError(err, 'Could not create the account');
      }
    });
  }

  // ----- loading -------------------------------------------------------------

  /**
   * Fetch everything the current role needs.
   *
   * The calls are independent and are fired together rather than chained. A
   * student needs two of them; a teacher needs three, because only a teacher has
   * work they have set.
   */
  loadEverything(): void {
    this.loadSubmissions();
    this.loadCourses();
    this.loadMarks();
    if (this.isTeacher) {
      this.loadAssignments();
    }
  }

  /**
   * Marks and the performance summary, fetched together.
   *
   * Two requests rather than one, and deliberately not derived from each other
   * in the browser: the summary is the SERVER's arithmetic, and asking it means
   * the report on screen and the report a teacher exports are the same numbers
   * the database would produce for anybody else.
   */
  loadMarks(): void {
    this.service.getAssessments().subscribe({
      next: (data) => this.marks = data,
      error: (err) => this.showError(err, 'Could not load marks')
    });
    this.service.getPerformance().subscribe({
      next: (data) => this.performance = data,
      error: (err) => this.showError(err, 'Could not load the performance summary')
    });
  }

  loadSubmissions(): void {
    this.loadingList = true;
    this.service.getSubmissions().subscribe({
      next: (data) => {
        this.loadingList = false;
        this.submissions = data;
        // Re-apply the current status filter to the new data, so a refresh does
        // not silently reset the view the user had set up.
        this.applyFilters();
        this.errorMessage = null;
      },
      error: (err) => {
        this.loadingList = false;
        this.showError(err, 'Could not load the work');
      }
    });
  }

  loadCourses(): void {
    this.service.getCourses().subscribe({
      next: (data) => this.courses = data,
      error: (err) => this.showError(err, 'Could not load your courses')
    });
  }

  loadAssignments(): void {
    this.service.getAssignments().subscribe({
      next: (data) => this.assignments = data,
      error: (err) => this.showError(err, 'Could not load the work you have set')
    });
  }

  // ----- setting work --------------------------------------------------------

  openSetWork(): void {
    this.showSetWork = true;
    this.newTitle = '';
    this.newDescription = '';
    this.newDueDate = '';
    this.selectedCourseIds.clear();
  }

  closeSetWork(): void {
    this.showSetWork = false;
  }

  toggleCourse(courseId: number): void {
    if (this.selectedCourseIds.has(courseId)) {
      this.selectedCourseIds.delete(courseId);
    } else {
      this.selectedCourseIds.add(courseId);
    }
  }

  isCourseSelected(courseId: number): boolean {
    return this.selectedCourseIds.has(courseId);
  }

  get canSetWork(): boolean {
    return this.newTitle.trim().length > 0 && this.selectedCourseIds.size > 0;
  }

  onSetWork(): void {
    if (!this.canSetWork) {
      return;
    }
    this.service.createAssignment(
      this.newTitle.trim(),
      this.newDescription.trim() || null,
      this.newDueDate || null,
      [...this.selectedCourseIds]
    ).subscribe({
      next: (created) => {
        const students = created.reduce((total, a) => total + a.studentCount, 0);
        this.timetableNotice =
          `Set for ${created.length} class${created.length === 1 ? '' : 'es'}, `
          + `reaching ${students} student${students === 1 ? '' : 's'}.`;
        this.showSetWork = false;
        this.loadEverything();
      },
      error: (err) => this.showError(err, 'Could not set the work')
    });
  }

  // ----- editing and deleting an assignment ----------------------------------

  startEditAssignment(a: Assignment): void {
    this.editingAssignmentId = a.id;
    this.editTitle = a.title;
    this.editDescription = a.description ?? '';
    this.editDueDate = a.dueDate ?? '';
  }

  cancelEditAssignment(): void {
    this.editingAssignmentId = null;
  }

  saveEditAssignment(id: number): void {
    const title = this.editTitle.trim();
    if (!title) {
      return;
    }
    this.service.updateAssignment(
      id, title, this.editDescription.trim() || null, this.editDueDate || null
    ).subscribe({
      next: () => {
        this.editingAssignmentId = null;
        this.loadEverything();
      },
      error: (err) => this.showError(err, 'Could not save the change')
    });
  }

  onDeleteAssignment(a: Assignment): void {
    if (!confirm(`Delete "${a.title}" for ${a.className}? This cannot be undone.`)) {
      return;
    }
    this.service.deleteAssignment(a.id).subscribe({
      next: () => this.loadEverything(),
      error: (err) => this.showError(err, 'Could not delete the work')
    });
  }

  // ----- a student's own work ------------------------------------------------

  /**
   * Upload the chosen PDF.
   *
   * The file is sent as it was chosen. Nothing here inspects or rewrites it -
   * the server checks the size, the declared type AND the leading bytes, because
   * a check performed in the browser is a check the browser can be made to skip.
   */
  onFileChosen(chosen: FileChosen): void {
    this.uploading = true;
    this.service.uploadFile(chosen.submissionId, chosen.file).subscribe({
      next: () => {
        this.uploading = false;
        this.errorMessage = null;
        this.loadSubmissions();
      },
      error: (err) => {
        this.uploading = false;
        this.showError(err, 'Could not upload that file');
      }
    });
  }

  onSubmitWork(id: number): void {
    this.service.submit(id).subscribe({
      next: () => this.loadEverything(),
      error: (err) => this.showError(err, 'Could not hand in the work')
    });
  }

  onUnsubmitWork(id: number): void {
    this.service.unsubmit(id).subscribe({
      next: () => this.loadEverything(),
      error: (err) => this.showError(err, 'Could not reopen the work')
    });
  }

  /**
   * Download a submitted PDF.
   *
   * The request cannot simply be a link: the endpoint needs the session cookie
   * AND, cross-origin, the browser will not attach it to a plain navigation. So
   * the bytes are fetched with the same credentialed HttpClient as everything
   * else, then handed to the user as a temporary object URL.
   *
   * revokeObjectURL matters. Each blob URL pins its data in memory until it is
   * released, so a teacher working through a class of thirty would otherwise
   * accumulate every PDF they had opened.
   */
  onDownload(submission: Submission): void {
    this.service.downloadFile(submission.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = submission.fileName ?? 'submission.pdf';
        anchor.click();
        URL.revokeObjectURL(url);
      },
      error: (err) => this.showError(err, 'Could not download that file')
    });
  }

  // ----- the timetable (teachers) --------------------------------------------

  openTimetable(): void {
    this.showTimetable = true;
    this.timetableNotice = null;
    this.service.getSubjects().subscribe({
      next: (data) => this.subjects = data,
      error: (err) => this.showError(err, 'Could not load subjects')
    });
    this.service.getClasses().subscribe({
      next: (data) => this.classes = data,
      error: (err) => this.showError(err, 'Could not load classes')
    });
  }

  closeTimetable(): void {
    this.showTimetable = false;
  }

  onCreateSubject(): void {
    const code = this.newSubjectCode.trim();
    const name = this.newSubjectName.trim();
    if (!code || !name) {
      return;
    }
    this.service.createSubject(code, name).subscribe({
      next: (created) => {
        this.subjects = [...this.subjects, created].sort((a, b) => a.name.localeCompare(b.name));
        this.newSubjectCode = '';
        this.newSubjectName = '';
        this.timetableNotice = `Subject "${created.name}" added.`;
      },
      error: (err) => this.showError(err, 'Could not add the subject')
    });
  }

  onCreateClass(): void {
    const name = this.newClassName.trim();
    if (!name) {
      return;
    }
    this.service.createClass(name).subscribe({
      next: (created) => {
        this.classes = [...this.classes, created].sort((a, b) => a.name.localeCompare(b.name));
        this.newClassName = '';
        this.timetableNotice = `Class "${created.name}" added.`;
      },
      error: (err) => this.showError(err, 'Could not add the class')
    });
  }

  onCreateCourse(): void {
    if (this.courseSubjectId === null || this.courseClassId === null) {
      return;
    }
    this.service.createCourse(this.courseSubjectId, this.courseClassId, null).subscribe({
      next: (created) => {
        this.timetableNotice = `You now teach ${created.label}.`;
        this.loadCourses();
      },
      error: (err) => this.showError(err, 'Could not set up that course')
    });
  }

  onEnrol(): void {
    const username = this.enrolUsername.trim();
    if (this.enrolClassId === null || !username) {
      return;
    }
    this.service.enrolStudent(this.enrolClassId, username).subscribe({
      next: () => {
        this.timetableNotice = `"${username}" enrolled.`;
        this.enrolUsername = '';
        this.openTimetable();   // refresh the counts
      },
      error: (err) => this.showError(err, 'Could not enrol that student')
    });
  }

  // ----- marks (EPIC-16) -----------------------------------------------------

  /** The title printed on the exported PDF, so a saved report says whose it is. */
  get markReportTitle(): string {
    return this.isTeacher
      ? `Marks report - ${this.user?.username ?? ''}`
      : `My marks - ${this.user?.username ?? ''}`;
  }

  openMarkEntry(): void {
    this.showMarkEntry = true;
    this.editingMarkId = null;
    this.markNotice = null;
    this.markCourseId = null;
    this.markStudent = '';
    this.markName = '';
    this.markScore = '';
    this.markMaxScore = '';
    this.markCandidates = [];
  }

  closeMarkEntry(): void {
    this.showMarkEntry = false;
    this.editingMarkId = null;
  }

  /**
   * When the course changes, fetch that class's register.
   *
   * The student picker offers only people actually enrolled, which matches what
   * the server will accept - it refuses a mark for somebody not in the class.
   * Offering every account and letting the save fail would be a worse way to
   * learn the same rule.
   */
  onMarkCourseChange(): void {
    this.markStudent = '';
    this.markCandidates = [];
    if (this.markCourseId === null) {
      return;
    }
    const course = this.courses.find(c => c.id === this.markCourseId);
    if (!course) {
      return;
    }
    this.service.getClassStudents(course.classId).subscribe({
      next: (names) => this.markCandidates = names,
      error: (err) => this.showError(err, 'Could not load the class register')
    });
  }

  /**
   * String(...) rather than calling .trim() directly, and that is not paranoia.
   *
   * These fields are declared as strings and bound to text inputs so the typed
   * value reaches the server unrounded. An earlier version used
   * <input type="number">, which makes Angular write a JavaScript NUMBER into
   * the model instead - and .trim() on a number throws, which killed this getter
   * and left the Save button permanently disabled with nothing on screen to say
   * why. Coercing here means a future change of input type degrades to a working
   * form rather than a dead one.
   */
  get canSaveMark(): boolean {
    const text = (value: unknown) => String(value ?? '').trim();

    if (!text(this.markName) || !text(this.markScore) || !text(this.markMaxScore)) {
      return false;
    }
    // Editing keeps the student and course it was recorded against; only a new
    // mark needs them chosen.
    if (this.editingMarkId === null) {
      return this.markCourseId !== null && this.markStudent.trim().length > 0;
    }
    return true;
  }

  onSaveMark(): void {
    if (!this.canSaveMark) {
      return;
    }

    const done = (verb: string) => () => {
      this.markNotice = `Mark ${verb}.`;
      this.errorMessage = null;
      this.showMarkEntry = false;
      this.editingMarkId = null;
      this.loadMarks();
    };

    // Same coercion as canSaveMark, for the same reason.
    const text = (value: unknown) => String(value ?? '').trim();

    if (this.editingMarkId !== null) {
      this.service.updateMark(this.editingMarkId, text(this.markName),
                              text(this.markScore), text(this.markMaxScore))
        .subscribe({
          next: done('corrected'),
          error: (err) => this.showError(err, 'Could not save the mark')
        });
      return;
    }

    this.service.recordMark(
      this.markCourseId!, text(this.markStudent), text(this.markName),
      text(this.markScore), text(this.markMaxScore), null
    ).subscribe({
      next: done('recorded'),
      error: (err) => this.showError(err, 'Could not record the mark')
    });
  }

  startEditMark(mark: Assessment): void {
    this.showMarkEntry = true;
    this.editingMarkId = mark.id;
    this.markNotice = null;
    this.markCourseId = mark.courseId;
    this.markStudent = mark.studentUsername;
    this.markName = mark.name;
    this.markScore = mark.score;
    this.markMaxScore = mark.maxScore;
  }

  onDeleteMark(mark: Assessment): void {
    if (!confirm(`Delete "${mark.name}" for ${mark.studentUsername}? This cannot be undone.`)) {
      return;
    }
    this.service.deleteMark(mark.id).subscribe({
      next: () => {
        this.markNotice = 'Mark deleted.';
        this.loadMarks();
      },
      error: (err) => this.showError(err, 'Could not delete the mark')
    });
  }

  // ----- error handling ------------------------------------------------------

  clearError(): void {
    this.errorMessage = null;
  }

  retry(): void {
    this.errorMessage = null;
    this.loadEverything();
  }

  /**
   * Turn a failed HTTP call into one sentence a human can read.
   *
   * The backend sends { status, error, message, path }, so when there IS a
   * message we show it - far more useful than a status number.
   */
  private showError(err: unknown, fallback: string): void {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 0) {
        // status 0 means the browser never got a response. It does NOT prove the
        // backend is down - a CORS rejection or an intercepted request looks
        // identical from here.
        const api = environment.apiBaseUrl || 'this site';
        this.errorMessage =
          `${fallback}: no response from the API at ${api}. ` +
          `The backend may be stopped, or the browser may have blocked the request ` +
          `(CORS, or a proxy intercepting localhost). Check the browser console — ` +
          `it names the actual cause.`;
        return;
      }
      if (err.status === 401) {
        // The session expired underneath us. Say so plainly and show the login
        // form again, rather than leaving a stale list on screen.
        this.user = null;
        this.submissions = [];
        this.visibleSubmissions = [];
        this.assignments = [];
        this.errorMessage = 'Your session has ended. Please sign in again.';
        return;
      }
      if (err.status === 413) {
        this.errorMessage = `${fallback}: that file is larger than the 10 MB limit.`;
        return;
      }
      const serverMessage = (err.error as { message?: string } | null)?.message;
      this.errorMessage = serverMessage
        ? `${fallback}: ${serverMessage}`
        : `${fallback} (HTTP ${err.status}).`;
      return;
    }
    this.errorMessage = fallback + '.';
  }
}
