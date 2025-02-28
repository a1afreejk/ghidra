/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.plugin.core.debug.service.breakpoint;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import java.util.stream.Collectors;

import org.apache.commons.collections4.IteratorUtils;

import com.google.common.collect.Range;

import generic.CatenatedCollection;
import ghidra.app.events.ProgramClosedPluginEvent;
import ghidra.app.events.ProgramOpenedPluginEvent;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.core.debug.DebuggerPluginPackage;
import ghidra.app.plugin.core.debug.event.TraceClosedPluginEvent;
import ghidra.app.plugin.core.debug.event.TraceOpenedPluginEvent;
import ghidra.app.plugin.core.debug.service.breakpoint.LogicalBreakpointInternal.ProgramBreakpoint;
import ghidra.app.services.*;
import ghidra.app.services.LogicalBreakpoint.State;
import ghidra.async.SwingExecutorService;
import ghidra.dbg.target.TargetBreakpointLocation;
import ghidra.dbg.target.TargetObject;
import ghidra.dbg.util.PathUtils;
import ghidra.framework.model.*;
import ghidra.framework.plugintool.*;
import ghidra.framework.plugintool.annotation.AutoServiceConsumed;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressRange;
import ghidra.program.model.listing.*;
import ghidra.program.util.*;
import ghidra.trace.model.*;
import ghidra.trace.model.Trace.TraceBreakpointChangeType;
import ghidra.trace.model.breakpoint.TraceBreakpoint;
import ghidra.trace.model.breakpoint.TraceBreakpointKind;
import ghidra.trace.model.program.TraceProgramView;
import ghidra.trace.util.TraceAddressSpace;
import ghidra.util.Msg;
import ghidra.util.datastruct.CollectionChangeListener;
import ghidra.util.datastruct.ListenerSet;

@PluginInfo(
	shortDescription = "Debugger logical breakpoints service plugin",
	description = "Aggregates breakpoints from open programs and live traces",
	category = PluginCategoryNames.DEBUGGER,
	packageName = DebuggerPluginPackage.NAME,
	status = PluginStatus.RELEASED,
	eventsConsumed = {
		ProgramOpenedPluginEvent.class,
		ProgramClosedPluginEvent.class,
		TraceOpenedPluginEvent.class,
		TraceClosedPluginEvent.class,
	},
	servicesRequired = {
		DebuggerTraceManagerService.class,
		DebuggerModelService.class,
		DebuggerStaticMappingService.class,
	},
	servicesProvided = {
		DebuggerLogicalBreakpointService.class,
	})
public class DebuggerLogicalBreakpointServicePlugin extends Plugin
		implements DebuggerLogicalBreakpointService {

	private static class AddCollector implements AutoCloseable {
		private final LogicalBreakpointsChangeListener l;
		private final Set<LogicalBreakpoint> added = new HashSet<>();
		private final Set<LogicalBreakpoint> updated;

		public AddCollector(LogicalBreakpointsChangeListener l, Set<LogicalBreakpoint> updated) {
			this.l = l;
			this.updated = updated;
		}

		protected void added(LogicalBreakpoint lb) {
			added.add(lb);
		}

		protected void updated(LogicalBreakpoint lb) {
			updated.add(lb);
		}

		protected void deconflict() {
			updated.removeAll(added); // Feels hackish, but it works
		}

		@Override
		public void close() {
			deconflict();
			if (!updated.isEmpty()) {
				l.breakpointsUpdated(updated);
			}
			if (!added.isEmpty()) {
				l.breakpointsAdded(added);
			}
		}
	}

	private static class RemoveCollector implements AutoCloseable {
		private final LogicalBreakpointsChangeListener l;
		private final Set<LogicalBreakpoint> removed = new HashSet<>();
		private final Set<LogicalBreakpoint> updated;

		public RemoveCollector(LogicalBreakpointsChangeListener l, Set<LogicalBreakpoint> updated) {
			this.l = l;
			this.updated = updated;
		}

		protected void updated(LogicalBreakpoint lb) {
			updated.add(lb);
		}

		protected void removed(LogicalBreakpoint lb) {
			removed.add(lb);
		}

		protected void deconflict() {
			updated.removeAll(removed); // Feels hackish, but it works
		}

		@Override
		public void close() {
			deconflict();
			if (!removed.isEmpty()) {
				l.breakpointsRemoved(removed);
			}
			if (!updated.isEmpty()) {
				l.breakpointsUpdated(updated);
			}
			updated.clear(); // Prevent duplication if used by another collector
		}
	}

	private static class ChangeCollector implements AutoCloseable {
		private final AddCollector a;
		private final RemoveCollector r;

		public ChangeCollector(LogicalBreakpointsChangeListener l) {
			Set<LogicalBreakpoint> updated = new HashSet<>();
			a = new AddCollector(l, updated);
			r = new RemoveCollector(l, updated);
		}

		@Override
		public void close() {
			r.deconflict();
			a.deconflict();
			r.close();
			a.close();
		}
	}

	protected class TrackRecordersListener implements CollectionChangeListener<TraceRecorder> {
		@Override
		public void elementAdded(TraceRecorder element) {
			processChange(c -> evtTraceRecordingStarted(c, element), "recordingStarted");
		}

		@Override
		public void elementRemoved(TraceRecorder element) {
			processChange(c -> evtTraceRecordingStopped(c, element), "recordingStopped");
		}
	}

	protected class TrackMappingsListener implements DebuggerStaticMappingChangeListener {
		@Override
		public void mappingsChanged(Set<Trace> affectedTraces, Set<Program> affectedPrograms) {
			processChange(c -> evtMappingsChanged(c, affectedTraces, affectedPrograms),
				"mappingsChanged");
		}
	}

	private class TraceBreakpointsListener extends TraceDomainObjectListener {
		private final InfoPerTrace info;
		private ChangeCollector c;

		public TraceBreakpointsListener(InfoPerTrace info) {
			// TODO: What if recorder advances past a trace breakpoint?
			// Tends never to happen during recording, since upper is unbounded
			// (Same in break provider for locations)
			this.info = info;

			listenForUntyped(DomainObject.DO_OBJECT_RESTORED, e -> objectRestored());
			listenFor(TraceBreakpointChangeType.ADDED, this::breakpointAdded);
			listenFor(TraceBreakpointChangeType.CHANGED, this::breakpointChanged);
			listenFor(TraceBreakpointChangeType.LIFESPAN_CHANGED, this::breakpointLifespanChanged);
			listenFor(TraceBreakpointChangeType.DELETED, this::breakpointDeleted);
		}

		@Override
		public void domainObjectChanged(DomainObjectChangedEvent ev) {
			processChange(c -> {
				// NB. This works because processEvent dispatches on a single thread
				this.c = c;
				super.domainObjectChanged(ev);
				this.c = null;
			}, "trace-domainObjectChanged");
		}

		private void objectRestored() {
			info.reloadBreakpoints(c);
		}

		private void breakpointAdded(TraceBreakpoint tb) {
			if (!tb.getLifespan().contains(info.recorder.getSnap())) {
				// NOTE: User/script probably added historical breakpoint
				return;
			}
			try {
				info.trackTraceBreakpoint(c.a, tb, false);
			}
			catch (TrackedTooSoonException e) {
				Msg.info(this, "Ignoring " + tb +
					" added until service has finished loading its trace");
			}
		}

		private void breakpointChanged(TraceBreakpoint tb) {
			if (!tb.getLifespan().contains(info.recorder.getSnap())) {
				return;
			}
			try {
				info.trackTraceBreakpoint(c.a, tb, true);
			}
			catch (TrackedTooSoonException e) {
				Msg.info(this, "Ignoring " + tb +
					" changed until service has finished loading its trace");
			}
			catch (NoSuchElementException e) {
				// TODO: This catch clause should not be necessary.
				Msg.error(this,
					"!!!! Object-based breakpoint emitted event without a spec: " + tb);
			}
		}

		private void breakpointLifespanChanged(TraceAddressSpace spaceIsNull,
				TraceBreakpoint tb, Range<Long> oldSpan, Range<Long> newSpan) {
			// NOTE: User/script probably modified historical breakpoint
			boolean isInOld = oldSpan.contains(info.recorder.getSnap());
			boolean isInNew = newSpan.contains(info.recorder.getSnap());
			if (isInOld == isInNew) {
				return;
			}
			if (isInOld) {
				info.forgetTraceBreakpoint(c.r, tb);
			}
			else {
				try {
					info.trackTraceBreakpoint(c.a, tb, false);
				}
				catch (TrackedTooSoonException e) {
					Msg.info(this, "Ignoring " + tb +
						" span changed until service has finished loading its trace");
				}
			}
		}

		private void breakpointDeleted(TraceBreakpoint tb) {
			if (!tb.getLifespan().contains(info.recorder.getSnap())) {
				// NOTE: User/script probably removed historical breakpoint
				// assert false;
				return;
			}
			info.forgetTraceBreakpoint(c.r, tb);
		}
	}

	private class ProgramBreakpointsListener extends TraceDomainObjectListener {
		private final InfoPerProgram info;
		private ChangeCollector c;

		public ProgramBreakpointsListener(InfoPerProgram info) {
			this.info = info;

			listenForUntyped(DomainObject.DO_OBJECT_RESTORED, e -> objectRestored());
			listenForUntyped(ChangeManager.DOCR_BOOKMARK_ADDED,
				onBreakpoint(this::breakpointBookmarkAdded));
			listenForUntyped(ChangeManager.DOCR_BOOKMARK_CHANGED,
				onBreakpoint(this::breakpointBookmarkChanged));
			listenForUntyped(ChangeManager.DOCR_BOOKMARK_REMOVED,
				onBreakpoint(this::breakpointBookmarkDeleted));
		}

		@Override
		public void domainObjectChanged(DomainObjectChangedEvent ev) {
			processChange(c -> {
				// NB. This works because processEvent dispatches on a single thread
				this.c = c;
				super.domainObjectChanged(ev);
				this.c = null;
			}, "program-domainObjectChanged");
		}

		private void objectRestored() {
			info.reloadBreakpoints(c);
			// NOTE: logical breakpoints should know which traces are affected
		}

		private Consumer<DomainObjectChangeRecord> onBreakpoint(Consumer<Bookmark> handler) {
			return rec -> {
				ProgramChangeRecord pcrec = (ProgramChangeRecord) rec; // Eww
				Bookmark pb = (Bookmark) pcrec.getObject(); // So gross
				String bmType = pb.getTypeString();
				if (LogicalBreakpoint.BREAKPOINT_ENABLED_BOOKMARK_TYPE.equals(bmType) ||
					LogicalBreakpoint.BREAKPOINT_DISABLED_BOOKMARK_TYPE.equals(bmType)) {
					handler.accept(pb);
				}
			};
		}

		private void breakpointBookmarkAdded(Bookmark pb) {
			info.trackProgramBreakpoint(c.a, pb);
		}

		private void breakpointBookmarkChanged(Bookmark pb) {
			breakpointBookmarkDeleted(pb, true);
			breakpointBookmarkAdded(pb);
		}

		private void breakpointBookmarkDeleted(Bookmark pb) {
			breakpointBookmarkDeleted(pb, false);
		}

		private void breakpointBookmarkDeleted(Bookmark pb, boolean forChange) {
			info.forgetProgramBreakpoint(c.r, pb, forChange);
		}
	}

	protected abstract class AbstractInfo {
		final NavigableMap<Address, Set<LogicalBreakpointInternal>> breakpointsByAddress =
			new TreeMap<>();

		public AbstractInfo() {
		}

		protected abstract void dispose(RemoveCollector r);

		protected abstract LogicalBreakpointInternal createLogicalBreakpoint(Address address,
				long length, Collection<TraceBreakpointKind> kinds);

		protected LogicalBreakpointInternal getOrCreateLogicalBreakpointFor(AddCollector a,
				Address address, TraceBreakpoint tb) throws TrackedTooSoonException {
			Set<LogicalBreakpointInternal> set =
				breakpointsByAddress.computeIfAbsent(address, __ -> new HashSet<>());
			for (LogicalBreakpointInternal lb : set) {
				if (lb.canMerge(tb)) {
					return lb;
				}
			}

			LogicalBreakpointInternal lb =
				createLogicalBreakpoint(address, tb.getLength(), tb.getKinds());
			set.add(lb);
			a.added(lb);
			return lb;
		}

		protected LogicalBreakpointInternal removeFromLogicalBreakpoint(RemoveCollector r,
				Address address, TraceBreakpoint tb) {
			Set<LogicalBreakpointInternal> set = breakpointsByAddress.get(address);
			if (set == null) {
				Msg.warn(this, "Breakpoint to remove is not present: " + tb + ", trace=" +
					tb.getTrace());
				return null;
			}
			for (LogicalBreakpointInternal lb : Set.copyOf(set)) {
				if (lb.untrackBreakpoint(tb)) {
					if (lb.isEmpty()) {
						// If being disposed, this info is no longer in the map
						removeLogicalBreakpoint(address, lb);
						removeLogicalBreakpointGlobally(lb);
						r.removed(lb);
					}
					else {
						r.updated(lb);
					}
					return lb;
				}
			}
			Msg.warn(this, "Breakpoint to remove is not present: " + tb + ", trace=" +
				tb.getTrace());
			return null;
		}

		protected boolean removeLogicalBreakpoint(Address address, LogicalBreakpoint lb) {
			Set<LogicalBreakpointInternal> set = breakpointsByAddress.get(address);
			if (set == null) {
				return false;
			}
			if (!set.remove(lb)) {
				return false;
			}
			if (set.isEmpty()) {
				breakpointsByAddress.remove(address);
			}
			return true;
		}

		protected boolean isMismapped(LogicalBreakpointInternal lb) {
			if (!(lb instanceof MappedLogicalBreakpoint)) {
				return false; // Can't be mis-mapped if not mapped
			}
			Set<Trace> extraneous = new HashSet<>(lb.getMappedTraces());
			extraneous.removeAll(traceInfos.keySet());
			if (!extraneous.isEmpty()) {
				return true;
			}
			for (InfoPerTrace ti : traceInfos.values()) {
				TraceLocation loc = ti.toDynamicLocation(lb.getProgramLocation());
				if (loc == null) {
					if (lb.getTraceAddress(ti.trace) != null) {
						return true;
					}
					continue;
				}
				if (!loc.getAddress().equals(lb.getTraceAddress(ti.trace))) {
					return true;
				}
			}
			return false;
		}

		protected void forgetMismappedBreakpoints(RemoveCollector r, Set<Trace> additionalTraces,
				Set<Program> additionalPrograms) {
			for (Set<LogicalBreakpointInternal> set : List.copyOf(breakpointsByAddress.values())) {
				for (LogicalBreakpointInternal lb : Set.copyOf(set)) {
					if (isMismapped(lb)) {
						removeLogicalBreakpointGlobally(lb);
						r.removed(lb);
						additionalTraces.addAll(lb.getParticipatingTraces());
					}
				}
			}
		}
	}

	protected class InfoPerTrace extends AbstractInfo {
		final TraceRecorder recorder;
		final Trace trace;
		final TraceBreakpointsListener breakpointListener;

		public InfoPerTrace(TraceRecorder recorder) {
			this.recorder = recorder;
			this.trace = recorder.getTrace();
			this.breakpointListener = new TraceBreakpointsListener(this);

			trace.addListener(breakpointListener);
		}

		@Override
		protected LogicalBreakpointInternal createLogicalBreakpoint(Address address, long length,
				Collection<TraceBreakpointKind> kinds) {
			return new LoneLogicalBreakpoint(recorder, address, length, kinds);
		}

		@Override
		protected void dispose(RemoveCollector r) {
			trace.removeListener(breakpointListener);
			forgetAllBreakpoints(r);
		}

		protected void reloadBreakpoints(ChangeCollector c) {
			forgetTraceInvalidBreakpoints(c.r);
			trackTraceLiveBreakpoints(c.a);
		}

		protected void forgetAllBreakpoints(RemoveCollector r) {
			Collection<TraceBreakpoint> live = new ArrayList<>();
			for (AddressRange range : trace.getBaseAddressFactory().getAddressSet()) {
				live.addAll(trace
						.getBreakpointManager()
						.getBreakpointsIntersecting(Range.singleton(recorder.getSnap()), range));
			}
			for (TraceBreakpoint tb : live) {
				forgetTraceBreakpoint(r, tb);
			}
		}

		protected void forgetTraceInvalidBreakpoints(RemoveCollector r) {
			// Breakpoint can become invalid because it is itself invalid (deleted)
			// Or because the mapping to static space has changed or become invalid

			for (Set<LogicalBreakpointInternal> set : List
					.copyOf(breakpointsByAddress.values())) {
				for (LogicalBreakpointInternal lb : Set.copyOf(set)) {
					for (TraceBreakpoint tb : Set.copyOf(lb.getTraceBreakpoints(trace))) {
						if (!trace.getBreakpointManager().getAllBreakpoints().contains(tb)) {
							forgetTraceBreakpoint(r, tb);
							continue;
						}
						ProgramLocation progLoc = computeStaticLocation(tb);
						if (!Objects.equals(lb.getProgramLocation(), progLoc)) {
							// NOTE: This can happen to Lone breakpoints.
							// (mis)Mapped ones should already be forgotten.
							forgetTraceBreakpoint(r, tb);
							continue;
						}
					}
				}
			}
		}

		protected void trackTraceLiveBreakpoints(AddCollector a) {
			Collection<TraceBreakpoint> live = new ArrayList<>();
			for (AddressRange range : trace.getBaseAddressFactory().getAddressSet()) {
				live.addAll(trace
						.getBreakpointManager()
						.getBreakpointsIntersecting(Range.singleton(recorder.getSnap()), range));
			}
			trackTraceBreakpoints(a, live);
		}

		protected void trackTraceBreakpoints(AddCollector a,
				Collection<TraceBreakpoint> breakpoints) {
			for (TraceBreakpoint tb : breakpoints) {
				try {
					trackTraceBreakpoint(a, tb, false);
				}
				catch (TrackedTooSoonException e) {
					// This can still happen during reload (on OBJECT_RESTORED)
					Msg.warn(this, "Might have lost track of a breakpoint: " + tb);
				}
			}
		}

		protected ProgramLocation computeStaticLocation(TraceBreakpoint tb) {
			if (traceManager == null ||
				!traceManager.getOpenTraces().contains(tb.getTrace())) {
				/**
				 * Mapping service will throw an exception otherwise. NB: When trace is opened,
				 * mapping service will fire events causing this service to coalesce affected
				 * breakpoints.
				 */
				return null;
			}
			return mappingService.getOpenMappedLocation(new DefaultTraceLocation(trace,
				null, Range.singleton(recorder.getSnap()), tb.getMinAddress()));
		}

		protected void trackTraceBreakpoint(AddCollector a, TraceBreakpoint tb, boolean forceUpdate)
				throws TrackedTooSoonException {
			Address traceAddr = tb.getMinAddress();
			ProgramLocation progLoc = computeStaticLocation(tb);
			LogicalBreakpointInternal lb;
			if (progLoc != null) {
				InfoPerProgram progInfo = programInfos.get(progLoc.getProgram());
				lb = progInfo.getOrCreateLogicalBreakpointFor(a, progLoc.getByteAddress(), tb);
			}
			else {
				lb = getOrCreateLogicalBreakpointFor(a, traceAddr, tb);
			}
			assert breakpointsByAddress.get(traceAddr).contains(lb);
			if (lb.trackBreakpoint(tb) || forceUpdate) {
				a.updated(lb);
			}
		}

		protected void forgetTraceBreakpoint(RemoveCollector r, TraceBreakpoint tb) {
			LogicalBreakpointInternal lb = removeFromLogicalBreakpoint(r, tb.getMinAddress(), tb);
			if (lb == null) {
				return; // Warnings already logged
			}
			assert lb.isEmpty() == (breakpointsByAddress.get(tb.getMinAddress()) == null ||
				!breakpointsByAddress.get(tb.getMinAddress()).contains(lb));
		}

		public TraceLocation toDynamicLocation(ProgramLocation loc) {
			return mappingService.getOpenMappedLocation(trace, loc, recorder.getSnap());
		}
	}

	protected class InfoPerProgram extends AbstractInfo {
		final Program program;
		final ProgramBreakpointsListener breakpointListener;

		public InfoPerProgram(Program program) {
			this.program = program;
			this.breakpointListener = new ProgramBreakpointsListener(this);

			program.addListener(breakpointListener);
		}

		protected void mapTraceAddresses(LogicalBreakpointInternal lb) {
			for (InfoPerTrace ti : traceInfos.values()) {
				TraceLocation loc = ti.toDynamicLocation(lb.getProgramLocation());
				if (loc == null) {
					continue;
				}
				lb.setTraceAddress(ti.recorder, loc.getAddress());
				ti.breakpointsByAddress.computeIfAbsent(loc.getAddress(), __ -> new HashSet<>())
						.add(lb);
			}
		}

		@Override
		protected LogicalBreakpointInternal createLogicalBreakpoint(Address address, long length,
				Collection<TraceBreakpointKind> kinds) {
			MappedLogicalBreakpoint lb =
				new MappedLogicalBreakpoint(program, address, length, kinds);
			mapTraceAddresses(lb);
			return lb;
		}

		protected LogicalBreakpointInternal getOrCreateLogicalBreakpointFor(AddCollector a,
				Bookmark pb) {
			Address address = pb.getAddress();
			Set<LogicalBreakpointInternal> set =
				breakpointsByAddress.computeIfAbsent(address, __ -> new HashSet<>());
			for (LogicalBreakpointInternal lb : set) {
				if (lb.canMerge(program, pb)) {
					return lb;
				}
			}

			LogicalBreakpointInternal lb =
				createLogicalBreakpoint(address, ProgramBreakpoint.lengthFromBookmark(pb),
					ProgramBreakpoint.kindsFromBookmark(pb));
			set.add(lb);
			a.added(lb);
			return lb;
		}

		protected LogicalBreakpointInternal removeFromLogicalBreakpoint(RemoveCollector r,
				Bookmark pb, boolean forChange) {
			Address address = pb.getAddress();
			Set<LogicalBreakpointInternal> set = breakpointsByAddress.get(address);
			if (set == null) {
				Msg.error(this, "Breakpoint " + pb + " was not tracked before removal!");
				return null;
			}
			for (LogicalBreakpointInternal lb : Set.copyOf(set)) {
				if (lb.untrackBreakpoint(program, pb)) {
					if (lb.isEmpty() && !forChange) {
						// If being disposed, this info may no longer be in the map
						removeLogicalBreakpoint(address, lb);
						removeLogicalBreakpointGlobally(lb);
						r.removed(lb);
					}
					else {
						r.updated(lb);
					}
					return lb;
				}
			}
			Msg.error(this, "Breakpoint " + pb + " was not tracked before removal!");
			return null;
		}

		@Override
		protected void dispose(RemoveCollector r) {
			program.removeListener(breakpointListener);
			forgetAllBreakpoints(r);
		}

		protected void reloadBreakpoints(ChangeCollector c) {
			forgetProgramInvalidBreakpoints(c.r);
			trackAllProgramBreakpoints(c.a);
		}

		protected void forgetAllBreakpoints(RemoveCollector r) {
			Collection<Bookmark> marks = new ArrayList<>();
			program.getBookmarkManager()
					.getBookmarksIterator(LogicalBreakpoint.BREAKPOINT_ENABLED_BOOKMARK_TYPE)
					.forEachRemaining(marks::add);
			program.getBookmarkManager()
					.getBookmarksIterator(LogicalBreakpoint.BREAKPOINT_DISABLED_BOOKMARK_TYPE)
					.forEachRemaining(marks::add);
			for (Bookmark pb : marks) {
				forgetProgramBreakpoint(r, pb, false);
			}
		}

		protected void forgetProgramInvalidBreakpoints(RemoveCollector r) {
			// Breakpoint can become invalid because it is itself invalid (deleted)
			// Or because its address moved (I think this can happen if the containing block moves)

			/**
			 * NOTE: A change in the program (other than bookmark address), should not affect the
			 * mapped trace addresses. That would require a change in the trace.
			 */
			for (Set<LogicalBreakpointInternal> set : List
					.copyOf(breakpointsByAddress.values())) {
				for (LogicalBreakpointInternal lb : Set.copyOf(set)) {
					Bookmark pb = lb.getProgramBookmark();
					if (pb == null) {
						continue;
					}
					if (pb != program.getBookmarkManager().getBookmark(pb.getId())) {
						forgetProgramBreakpoint(r, pb, false);
						continue;
					}
					if (!lb.getProgramLocation().getByteAddress().equals(pb.getAddress())) {
						forgetProgramBreakpoint(r, pb, false);
						continue;
					}
				}
			}
		}

		protected void trackAllProgramBreakpoints(AddCollector a) {
			BookmarkManager bookmarks = program.getBookmarkManager();
			trackProgramBreakpoints(a, IteratorUtils.asIterable(bookmarks
					.getBookmarksIterator(LogicalBreakpoint.BREAKPOINT_ENABLED_BOOKMARK_TYPE)));
			trackProgramBreakpoints(a, IteratorUtils.asIterable(bookmarks
					.getBookmarksIterator(LogicalBreakpoint.BREAKPOINT_DISABLED_BOOKMARK_TYPE)));
		}

		protected void trackProgramBreakpoints(AddCollector a, Iterable<Bookmark> bptMarks) {
			for (Bookmark pb : bptMarks) {
				trackProgramBreakpoint(a, pb);
			}
		}

		protected void trackProgramBreakpoint(AddCollector a, Bookmark pb) {
			LogicalBreakpointInternal lb = getOrCreateLogicalBreakpointFor(a, pb);
			assert breakpointsByAddress.get(pb.getAddress()).contains(lb);
			if (lb.trackBreakpoint(pb)) {
				a.updated(lb);
			}
		}

		protected void forgetProgramBreakpoint(RemoveCollector r, Bookmark pb, boolean forChange) {
			LogicalBreakpointInternal lb = removeFromLogicalBreakpoint(r, pb, forChange);
			if (lb == null) {
				return;
			}
			assert isConsistentAfterRemoval(pb, lb, forChange);
		}

		private boolean isConsistentAfterRemoval(Bookmark pb, LogicalBreakpointInternal lb,
				boolean forChange) {
			Set<LogicalBreakpointInternal> present =
				breakpointsByAddress.get(pb.getAddress());
			boolean shouldBeAbsent = lb.isEmpty() && !forChange;
			boolean isAbsent = present == null || !present.contains(lb);
			return shouldBeAbsent == isAbsent;
		}
	}

	// @AutoServiceConsumed via method
	private DebuggerModelService modelService;
	@AutoServiceConsumed
	private DebuggerTraceManagerService traceManager;
	// @AutoServiceConsumed via method
	private DebuggerStaticMappingService mappingService;
	@SuppressWarnings("unused")
	private final AutoService.Wiring autoServiceWiring;

	private final Object lock = new Object();
	private final ListenerSet<LogicalBreakpointsChangeListener> changeListeners =
		new ListenerSet<>(LogicalBreakpointsChangeListener.class);

	private final TrackRecordersListener recorderListener = new TrackRecordersListener();
	private final TrackMappingsListener mappingListener = new TrackMappingsListener();

	private final Map<Trace, InfoPerTrace> traceInfos = new HashMap<>();
	private final Map<Program, InfoPerProgram> programInfos = new HashMap<>();
	private final Collection<AbstractInfo> allInfos =
		new CatenatedCollection<>(traceInfos.values(), programInfos.values());
	private final ExecutorService executor = SwingExecutorService.MAYBE_NOW;

	public DebuggerLogicalBreakpointServicePlugin(PluginTool tool) {
		super(tool);
		this.autoServiceWiring = AutoService.wireServicesProvidedAndConsumed(this);
	}

	protected void processChange(Consumer<ChangeCollector> processor, String description) {
		executor.submit(() -> {
			// Invoke change callbacks without the lock! (try must surround sync)
			try (ChangeCollector c = new ChangeCollector(changeListeners.fire)) {
				synchronized (lock) {
					processor.accept(c);
				}
			}
			catch (Throwable t) {
				Msg.error(this, "Could not process event " + description, t);
			}
		});
	}

	protected void evtMappingsChanged(ChangeCollector c, Set<Trace> affectedTraces,
			Set<Program> affectedPrograms) {
		Set<Trace> additionalTraces = new HashSet<>(affectedTraces);
		Set<Program> additionalPrograms = new HashSet<>(affectedPrograms);
		// Though all mapped breakpoints are in program infos,
		// That program may not be in the affected list, esp., if just closed
		for (Trace t : affectedTraces) {
			InfoPerTrace info = traceInfos.get(t);
			if (info != null) {
				info.forgetMismappedBreakpoints(c.r, additionalTraces,
					additionalPrograms);
			}
		}
		for (Program p : affectedPrograms) {
			InfoPerProgram info = programInfos.get(p);
			if (info != null) {
				info.forgetMismappedBreakpoints(c.r, additionalTraces,
					additionalPrograms);
			}
		}
		for (Trace t : additionalTraces) {
			InfoPerTrace info = traceInfos.get(t);
			if (info != null) {
				info.reloadBreakpoints(c);
			}
		}
		for (Program p : additionalPrograms) {
			InfoPerProgram info = programInfos.get(p);
			if (info != null) {
				info.reloadBreakpoints(c);
			}
		}
	}

	protected void removeLogicalBreakpointGlobally(LogicalBreakpoint lb) {
		ProgramLocation pLoc = lb.getProgramLocation();
		if (pLoc != null) {
			InfoPerProgram info = programInfos.get(pLoc.getProgram());
			if (info != null) { // Maybe the program was just closed
				info.removeLogicalBreakpoint(pLoc.getByteAddress(), lb);
			}
		}
		for (Trace t : lb.getMappedTraces()) {
			Address tAddr = lb.getTraceAddress(t);
			InfoPerTrace info = traceInfos.get(t);
			if (info != null) { // Maybe the trace was just closed
				info.removeLogicalBreakpoint(tAddr, lb);
			}
		}
	}

	@AutoServiceConsumed
	private void setModelService(DebuggerModelService modelService) {
		if (this.modelService != null) {
			this.modelService.removeTraceRecordersChangedListener(recorderListener);
		}
		this.modelService = modelService;
		if (this.modelService != null) {
			this.modelService.addTraceRecordersChangedListener(recorderListener);
		}
	}

	@AutoServiceConsumed
	private void setMappingService(DebuggerStaticMappingService mappingService) {
		if (this.mappingService != null) {
			this.mappingService.removeChangeListener(mappingListener);
		}
		this.mappingService = mappingService;
		if (this.mappingService != null) {
			this.mappingService.addChangeListener(mappingListener);
		}
	}

	private void programOpened(Program program) {
		processChange(c -> evtProgramOpened(c, program), "programOpened");
	}

	private void evtProgramOpened(ChangeCollector c, Program program) {
		if (program instanceof TraceProgramView) {
			// TODO: Not a good idea for user/script, but not prohibited
			return;
		}
		InfoPerProgram info = new InfoPerProgram(program);
		if (programInfos.put(program, info) != null) {
			throw new AssertionError("Already tracking program breakpoints");
		}
		info.reloadBreakpoints(c);
	}

	private void programClosed(Program program) {
		processChange(c -> evtProgramClosed(c, program), "programClosed");
	}

	private void evtProgramClosed(ChangeCollector c, Program program) {
		if (program instanceof TraceProgramView) {
			return;
		}
		programInfos.remove(program).dispose(c.r);
		// The mapping removals, if any, will clean up related traces
	}

	private void doTrackTrace(ChangeCollector c, Trace trace, TraceRecorder recorder) {
		if (traceInfos.containsKey(trace)) {
			Msg.warn(this, "Already tracking trace breakpoints");
			return;
		}
		InfoPerTrace info = new InfoPerTrace(recorder);
		traceInfos.put(trace, info);
		info.reloadBreakpoints(c);
	}

	private void doUntrackTrace(ChangeCollector c, Trace trace) {
		synchronized (lock) {
			InfoPerTrace tInfo = traceInfos.remove(trace);
			if (tInfo == null) { // Could be from trace close or recorder stop
				return;
			}
			tInfo.dispose(c.r);
			for (InfoPerProgram pInfo : programInfos.values()) {
				for (Set<LogicalBreakpointInternal> set : pInfo.breakpointsByAddress.values()) {
					for (LogicalBreakpointInternal lb : set) {
						lb.removeTrace(trace);
						c.a.updated(lb);
					}
				}
			}
		}
	}

	private void evtTraceRecordingStarted(ChangeCollector c, TraceRecorder recorder) {
		Trace trace = recorder.getTrace();
		if (!traceManager.getOpenTraces().contains(trace)) {
			return;
		}
		doTrackTrace(c, trace, recorder);
	}

	private void evtTraceRecordingStopped(ChangeCollector c, TraceRecorder recorder) {
		doUntrackTrace(c, recorder.getTrace());
	}

	private void traceOpened(Trace trace) {
		processChange(c -> {
			TraceRecorder recorder = modelService.getRecorder(trace);
			if (recorder == null) {
				return;
			}
			doTrackTrace(c, trace, recorder);
		}, "traceOpened");
	}

	private void traceClosed(Trace trace) {
		processChange(c -> doUntrackTrace(c, trace), "traceClosed");
	}

	@Override
	public Set<LogicalBreakpoint> getAllBreakpoints() {
		// TODO: Could probably track this set
		synchronized (lock) {
			Set<LogicalBreakpoint> records = new HashSet<>();
			for (AbstractInfo info : allInfos) {
				for (Set<LogicalBreakpointInternal> recsAtAddress : info.breakpointsByAddress
						.values()) {
					records.addAll(recsAtAddress);
				}
			}
			return records;
		}
	}

	protected static <K, V> NavigableMap<K, Set<V>> copyOf(
			NavigableMap<? extends K, ? extends Set<? extends V>> map) {
		NavigableMap<K, Set<V>> result = new TreeMap<>();
		for (Map.Entry<? extends K, ? extends Set<? extends V>> ent : map.entrySet()) {
			result.put(ent.getKey(), new HashSet<>(ent.getValue()));
		}
		return result;
	}

	@Override
	public NavigableMap<Address, Set<LogicalBreakpoint>> getBreakpoints(Program program) {
		synchronized (lock) {
			InfoPerProgram info = programInfos.get(program);
			if (info == null) {
				return Collections.emptyNavigableMap();
			}
			return copyOf(info.breakpointsByAddress);
		}
	}

	@Override
	public NavigableMap<Address, Set<LogicalBreakpoint>> getBreakpoints(Trace trace) {
		synchronized (lock) {
			InfoPerTrace info = traceInfos.get(trace);
			if (info == null) {
				return Collections.emptyNavigableMap();
			}
			return copyOf(info.breakpointsByAddress);
		}
	}

	protected Set<LogicalBreakpoint> doGetBreakpointsAt(AbstractInfo info, Address address) {
		Set<LogicalBreakpointInternal> set = info.breakpointsByAddress.get(address);
		if (set == null) {
			return Set.of();
		}
		return new HashSet<>(set);
	}

	@Override
	public Set<LogicalBreakpoint> getBreakpointsAt(Program program, Address address) {
		synchronized (lock) {
			InfoPerProgram info = programInfos.get(program);
			if (info == null) {
				return Set.of();
			}
			return doGetBreakpointsAt(info, address);
		}
	}

	@Override
	public Set<LogicalBreakpoint> getBreakpointsAt(Trace trace, Address address) {
		synchronized (lock) {
			InfoPerTrace info = traceInfos.get(trace);
			if (info == null) {
				return Set.of();
			}
			return doGetBreakpointsAt(info, address).stream()
					.filter(lb -> lb.computeStateForTrace(trace) != State.NONE)
					.collect(Collectors.toSet());
		}
	}

	@Override
	public LogicalBreakpoint getBreakpoint(TraceBreakpoint bpt) {
		Trace trace = bpt.getTrace();
		synchronized (lock) {
			for (LogicalBreakpoint lb : getBreakpointsAt(trace, bpt.getMinAddress())) {
				if (lb.getTraceBreakpoints(trace).contains(bpt)) {
					return lb;
				}
			}
		}
		return null;
	}

	@Override
	public Set<LogicalBreakpoint> getBreakpointsAt(ProgramLocation loc) {
		return DebuggerLogicalBreakpointService.programOrTrace(loc,
			this::getBreakpointsAt,
			this::getBreakpointsAt);
	}

	@Override
	public void addChangeListener(LogicalBreakpointsChangeListener l) {
		changeListeners.add(l);
	}

	@Override
	public void removeChangeListener(LogicalBreakpointsChangeListener l) {
		changeListeners.remove(l);
	}

	@Override
	public CompletableFuture<Void> changesSettled() {
		return CompletableFuture.supplyAsync(() -> null, executor);
	}

	protected MappedLogicalBreakpoint synthesizeLogicalBreakpoint(Program program, Address address,
			long length, Collection<TraceBreakpointKind> kinds) {
		/**
		 * TODO: This is a bit of a waste, but it gets the job done. The instance created here is
		 * dropped by its caller. It's just used for its breakpoint placement logic, part of which I
		 * had to re-implement here, anyway. The actual logical breakpoint is created by event
		 * processors.
		 */
		MappedLogicalBreakpoint lb = new MappedLogicalBreakpoint(program, address, length, kinds);
		synchronized (lock) {
			for (InfoPerTrace ti : traceInfos.values()) {
				TraceLocation loc = ti.toDynamicLocation(lb.getProgramLocation());
				if (loc == null) {
					continue;
				}
				lb.setTraceAddress(ti.recorder, loc.getAddress());
			}
		}
		return lb;
	}

	@Override
	public CompletableFuture<Void> placeBreakpointAt(Program program, Address address, long length,
			Collection<TraceBreakpointKind> kinds, String name) {
		MappedLogicalBreakpoint lb = synthesizeLogicalBreakpoint(program, address, length, kinds);
		return lb.enableWithName(name);
	}

	@Override
	public CompletableFuture<Void> placeBreakpointAt(Trace trace, Address address, long length,
			Collection<TraceBreakpointKind> kinds, String name) {
		TraceRecorder recorder = modelService.getRecorder(trace);
		if (recorder == null) {
			throw new IllegalArgumentException("Given trace is not live");
		}

		ProgramLocation staticLocation = mappingService.getOpenMappedLocation(
			new DefaultTraceLocation(trace, null, Range.singleton(recorder.getSnap()), address));
		if (staticLocation == null) {
			return new LoneLogicalBreakpoint(recorder, address, length, kinds)
					.enableForTrace(trace);
		}

		MappedLogicalBreakpoint lb = new MappedLogicalBreakpoint(staticLocation.getProgram(),
			staticLocation.getAddress(), length, kinds);
		lb.setTraceAddress(recorder, address);
		lb.enableForProgramWithName(name);
		return lb.enableForTrace(trace);
	}

	@Override
	public CompletableFuture<Void> placeBreakpointAt(ProgramLocation loc, long length,
			Collection<TraceBreakpointKind> kinds, String name) {
		return DebuggerLogicalBreakpointService.programOrTrace(loc,
			(p, a) -> placeBreakpointAt(p, a, length, kinds, name),
			(t, a) -> placeBreakpointAt(t, a, length, kinds, name));
	}

	protected CompletableFuture<Void> actOnAll(Collection<LogicalBreakpoint> col, Trace trace,
			Consumer<LogicalBreakpoint> consumerForProgram,
			BiConsumer<BreakpointActionSet, LogicalBreakpointInternal> consumerForTarget) {
		BreakpointActionSet actions = new BreakpointActionSet();
		for (LogicalBreakpoint lb : col) {
			Set<Trace> participants = lb.getParticipatingTraces();
			if (trace == null || participants.isEmpty() || participants.equals(Set.of(trace))) {
				consumerForProgram.accept(lb);
			}
			if (!(lb instanceof LogicalBreakpointInternal)) {
				continue;
			}
			LogicalBreakpointInternal lbi = (LogicalBreakpointInternal) lb;
			consumerForTarget.accept(actions, lbi);
		}
		return actions.execute();
	}

	@Override
	public CompletableFuture<Void> enableAll(Collection<LogicalBreakpoint> col, Trace trace) {
		return actOnAll(col, trace, LogicalBreakpoint::enableForProgram,
			(actions, lbi) -> lbi.planEnable(actions, trace));
	}

	@Override
	public CompletableFuture<Void> disableAll(Collection<LogicalBreakpoint> col, Trace trace) {
		return actOnAll(col, trace, LogicalBreakpoint::disableForProgram,
			(actions, lbi) -> lbi.planDisable(actions, trace));
	}

	@Override
	public CompletableFuture<Void> deleteAll(Collection<LogicalBreakpoint> col, Trace trace) {
		return actOnAll(col, trace, lb -> {
			// Do not delete bookmark from dynamic context
			if (trace == null) {
				lb.deleteForProgram();
			}
		}, (actions, lbi) -> lbi.planDelete(actions, trace));
	}

	protected CompletableFuture<Void> actOnLocs(Collection<TraceBreakpoint> col,
			BiConsumer<BreakpointActionSet, TargetBreakpointLocation> locConsumer,
			Consumer<LogicalBreakpoint> progConsumer) {
		BreakpointActionSet actions = new BreakpointActionSet();
		for (TraceBreakpoint tb : col) {
			LogicalBreakpoint lb = getBreakpoint(tb);
			if (col.containsAll(lb.getTraceBreakpoints())) {
				progConsumer.accept(lb);
			}
			TraceRecorder recorder = modelService.getRecorder(tb.getTrace());
			if (recorder == null) {
				continue;
			}
			List<String> path = PathUtils.parse(tb.getPath());
			TargetObject object = recorder.getTarget().getModel().getModelObject(path);
			if (!(object instanceof TargetBreakpointLocation)) {
				Msg.error(this, tb.getPath() + " is not a target breakpoint location");
				continue;
			}
			TargetBreakpointLocation loc = (TargetBreakpointLocation) object;
			locConsumer.accept(actions, loc);
		}
		return actions.execute();
	}

	@Override
	public CompletableFuture<Void> enableLocs(Collection<TraceBreakpoint> col) {
		return actOnLocs(col, BreakpointActionSet::planEnable, LogicalBreakpoint::enableForProgram);
	}

	@Override
	public CompletableFuture<Void> disableLocs(Collection<TraceBreakpoint> col) {
		return actOnLocs(col, BreakpointActionSet::planDisable,
			LogicalBreakpoint::disableForProgram);
	}

	@Override
	public CompletableFuture<Void> deleteLocs(Collection<TraceBreakpoint> col) {
		return actOnLocs(col, BreakpointActionSet::planDelete, lb -> {
			// Never delete bookmark when user requests deleting locations
		});
	}

	@Override
	public CompletableFuture<Set<LogicalBreakpoint>> toggleBreakpointsAt(ProgramLocation loc,
			Supplier<CompletableFuture<Set<LogicalBreakpoint>>> placer) {
		Set<LogicalBreakpoint> bs = getBreakpointsAt(loc);
		if (bs == null || bs.isEmpty()) {
			return placer.get();
		}
		State state = computeState(bs, loc);
		/**
		 * If we're in the static listing, this will return null, indicating we should use the
		 * program's perspective. The methods taking trace should accept a null trace and behave
		 * accordingly. If in the dynamic listing, we act in the context of the returned trace.
		 */
		Trace trace =
			DebuggerLogicalBreakpointService.programOrTrace(loc, (p, a) -> null, (t, a) -> t);
		boolean mapped = anyMapped(bs, trace);
		State toggled = state.getToggled(mapped);
		if (toggled.isEnabled()) {
			return enableAll(bs, trace).thenApply(__ -> bs);
		}
		return disableAll(bs, trace).thenApply(__ -> bs);
	}

	@Override
	public void processEvent(PluginEvent event) {
		if (event instanceof ProgramOpenedPluginEvent) {
			ProgramOpenedPluginEvent openedEvt = (ProgramOpenedPluginEvent) event;
			programOpened(openedEvt.getProgram());
		}
		else if (event instanceof ProgramClosedPluginEvent) {
			ProgramClosedPluginEvent closedEvt = (ProgramClosedPluginEvent) event;
			programClosed(closedEvt.getProgram());
		}
		else if (event instanceof TraceOpenedPluginEvent) {
			TraceOpenedPluginEvent openedEvt = (TraceOpenedPluginEvent) event;
			traceOpened(openedEvt.getTrace());
		}
		else if (event instanceof TraceClosedPluginEvent) {
			TraceClosedPluginEvent closedEvt = (TraceClosedPluginEvent) event;
			traceClosed(closedEvt.getTrace());
		}
	}
}
