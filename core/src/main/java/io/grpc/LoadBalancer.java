/*
 * Copyright 2016 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A pluggable component that receives resolved addresses from {@link NameResolver} and provides the
 * channel a usable subchannel when asked.
 *
 * <h3>Overview</h3>
 *
 * <p>A LoadBalancer typically implements three interfaces:
 * <ol>
 *   <li>{@link LoadBalancer} is the main interface.  All methods on it are invoked sequentially
 *       in the same <strong>synchronization context</strong> (see next section) as returned by
 *       {@link io.grpc.LoadBalancer.Helper#getSynchronizationContext}.  It receives the results
 *       from the {@link NameResolver}, updates of subchannels' connectivity states, and the
 *       channel's request for the LoadBalancer to shutdown.</li>
 *   <li>{@link SubchannelPicker SubchannelPicker} does the actual load-balancing work.  It selects
 *       a {@link Subchannel Subchannel} for each new RPC.</li>
 *   <li>{@link Factory Factory} creates a new {@link LoadBalancer} instance.
 * </ol>
 *
 * <p>{@link Helper Helper} is implemented by gRPC library and provided to {@link Factory
 * Factory}. It provides functionalities that a {@code LoadBalancer} implementation would typically
 * need.
 *
 * <h3>The Synchronization Context</h3>
 *
 * <p>All methods on the {@link LoadBalancer} interface are called from a Synchronization Context,
 * meaning they are serialized, thus the balancer implementation doesn't need to worry about
 * synchronization among them.  {@link io.grpc.LoadBalancer.Helper#getSynchronizationContext}
 * allows implementations to schedule tasks to be run in the same Synchronization Context, with or
 * without a delay, thus those tasks don't need to worry about synchronizing with the balancer
 * methods.
 * 
 * <p>However, the actual running thread may be the network thread, thus the following rules must be
 * followed to prevent blocking or even dead-locking in a network:
 *
 * <ol>
 *
 *   <li><strong>Never block in the Synchronization Context</strong>.  The callback methods must
 *   return quickly.  Examples or work that must be avoided: CPU-intensive calculation, waiting on
 *   synchronization primitives, blocking I/O, blocking RPCs, etc.</li>
 *
 *   <li><strong>Avoid calling into other components with lock held</strong>.  The Synchronization
 *   Context may be under a lock, e.g., the transport lock of OkHttp.  If your LoadBalancer has a
 *   lock, holds the lock in a callback method (e.g., {@link #handleSubchannelState
 *   handleSubchannelState()}) while calling into another class that may involve locks, be cautious
 *   of deadlock.  Generally you wouldn't need any locking in the LoadBalancer.</li>
 *
 * </ol>
 *
 * <h3>The canonical implementation pattern</h3>
 *
 * <p>A {@link LoadBalancer} keeps states like the latest addresses from NameResolver, the
 * Subchannel(s) and their latest connectivity states.  These states are mutated within the Channel
 * Executor.
 *
 * <p>A typical {@link SubchannelPicker SubchannelPicker} holds a snapshot of these states.  It may
 * have its own states, e.g., a picker from a round-robin load-balancer may keep a pointer to the
 * next Subchannel, which are typically mutated by multiple threads.  The picker should only mutate
 * its own state, and should not mutate or re-acquire the states of the LoadBalancer.  This way the
 * picker only needs to synchronize its own states, which is typically trivial to implement.
 *
 * <p>When the LoadBalancer states changes, e.g., Subchannels has become or stopped being READY, and
 * we want subsequent RPCs to use the latest list of READY Subchannels, LoadBalancer would create
 * a new picker, which holds a snapshot of the latest Subchannel list.  Refer to the javadoc of
 * {@link #handleSubchannelState handleSubchannelState()} how to do this properly.
 *
 * <p>No synchronization should be necessary between LoadBalancer and its pickers if you follow
 * the pattern above.  It may be possible to implement in a different way, but that would usually
 * result in more complicated threading.
 *
 * @since 1.2.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
@NotThreadSafe
public abstract class LoadBalancer {
  /**
   * The load-balancing config converted from an JSON object injected by the GRPC library.
   *
   * <p>{@link NameResolver}s should not produce this attribute.
   */
  @NameResolver.ResolutionResultAttr
  public static final Attributes.Key<Map<String, ?>> ATTR_LOAD_BALANCING_CONFIG =
      Attributes.Key.create("io.grpc.LoadBalancer.loadBalancingConfig");

  /**
   * Handles newly resolved server groups and metadata attributes from name resolution system.
   * {@code servers} contained in {@link EquivalentAddressGroup} should be considered equivalent
   * but may be flattened into a single list if needed.
   *
   * <p>Implementations should not modify the given {@code servers}.
   *
   * @param servers the resolved server addresses, never empty.
   * @param attributes extra information from naming system.
   * @deprecated override {@link #handleResolvedAddresses(ResolvedAddresses) instead}
   * @since 1.2.0
   */
  @Deprecated
  public void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> servers,
      @NameResolver.ResolutionResultAttr Attributes attributes) {
    throw new UnsupportedOperationException("Not implemented");
  }

  /**
   * Handles newly resolved server groups and metadata attributes from name resolution system.
   * {@code servers} contained in {@link EquivalentAddressGroup} should be considered equivalent
   * but may be flattened into a single list if needed.
   *
   * <p>Implementations should not modify the given {@code servers}.
   *
   * @param resolvedAddresses the resolved server addresses, attributes, and config.
   * @since 1.21.0
   */
  @SuppressWarnings("deprecation")
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    handleResolvedAddressGroups(resolvedAddresses.getServers(), resolvedAddresses.getAttributes());
  }

  /**
   * Represents a combination of the resolved server address, associated attributes and a load
   * balancing policy config.  The config is from the {@link
   * LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)}.
   *
   * @since 1.21.0
   */
  public static final class ResolvedAddresses {
    private final List<EquivalentAddressGroup> servers;
    @NameResolver.ResolutionResultAttr
    private final Attributes attributes;
    @Nullable
    private final Object loadBalancingPolicyConfig;
    // Make sure to update toBuilder() below!

    private ResolvedAddresses(
        List<EquivalentAddressGroup> servers,
        @NameResolver.ResolutionResultAttr Attributes attributes,
        Object loadBalancingPolicyConfig) {
      this.servers =
          Collections.unmodifiableList(new ArrayList<>(checkNotNull(servers, "servers")));
      this.attributes = checkNotNull(attributes, "attributes");
      this.loadBalancingPolicyConfig = loadBalancingPolicyConfig;
    }

    /**
     * Factory for constructing a new Builder.
     *
     * @since 1.21.0
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    /**
     * Converts this back to a builder.
     *
     * @since 1.21.0
     */
    public Builder toBuilder() {
      return newBuilder()
          .setServers(servers)
          .setAttributes(attributes)
          .setLoadBalancingPolicyConfig(loadBalancingPolicyConfig);
    }

    /**
     * Gets the server addresses.
     *
     * @since 1.21.0
     */
    public List<EquivalentAddressGroup> getServers() {
      return servers;
    }

    /**
     * Gets the attributes associated with these addresses.  If this was not previously set,
     * {@link Attributes#EMPTY} will be returned.
     *
     * @since 1.21.0
     */
    @NameResolver.ResolutionResultAttr
    public Attributes getAttributes() {
      return attributes;
    }

    /**
     * Gets the domain specific load balancing policy.  This is the config produced by
     * {@link LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)}.
     *
     * @since 1.21.0
     */
    @Nullable
    public Object getLoadBalancingPolicyConfig() {
      return loadBalancingPolicyConfig;
    }

    /**
     * Builder for {@link ResolvedAddresses}.
     */
    public static final class Builder {
      private List<EquivalentAddressGroup> servers;
      @NameResolver.ResolutionResultAttr
      private Attributes attributes = Attributes.EMPTY;
      @Nullable
      private Object loadBalancingPolicyConfig;

      Builder() {}

      /**
       * Sets the servers.  This field is required.
       *
       * @return this.
       */
      public Builder setServers(List<EquivalentAddressGroup> servers) {
        this.servers = servers;
        return this;
      }

      /**
       * Sets the attributes.  This field is optional; if not called, {@link Attributes#EMPTY}
       * will be used.
       *
       * @return this.
       */
      public Builder setAttributes(@NameResolver.ResolutionResultAttr Attributes attributes) {
        this.attributes = attributes;
        return this;
      }

      /**
       * Sets the load balancing policy config. This field is optional.
       *
       * @return this.
       */
      public Builder setLoadBalancingPolicyConfig(@Nullable Object loadBalancingPolicyConfig) {
        this.loadBalancingPolicyConfig = loadBalancingPolicyConfig;
        return this;
      }

      /**
       * Constructs the {@link ResolvedAddresses}.
       */
      public ResolvedAddresses build() {
        return new ResolvedAddresses(servers, attributes, loadBalancingPolicyConfig);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("servers", servers)
          .add("attributes", attributes)
          .add("loadBalancingPolicyConfig", loadBalancingPolicyConfig)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(servers, attributes, loadBalancingPolicyConfig);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ResolvedAddresses)) {
        return false;
      }
      ResolvedAddresses that = (ResolvedAddresses) obj;
      return Objects.equal(this.servers, that.servers)
          && Objects.equal(this.attributes, that.attributes)
          && Objects.equal(this.loadBalancingPolicyConfig, that.loadBalancingPolicyConfig);
    }
  }

  /**
   * Handles an error from the name resolution system.
   *
   * @param error a non-OK status
   * @since 1.2.0
   */
  public abstract void handleNameResolutionError(Status error);

  /**
   * Handles a state change on a Subchannel.
   *
   * <p>The initial state of a Subchannel is IDLE. You won't get a notification for the initial IDLE
   * state.
   *
   * <p>If the new state is not SHUTDOWN, this method should create a new picker and call {@link
   * Helper#updateBalancingState Helper.updateBalancingState()}.  Failing to do so may result in
   * unnecessary delays of RPCs. Please refer to {@link PickResult#withSubchannel
   * PickResult.withSubchannel()}'s javadoc for more information.
   *
   * <p>SHUTDOWN can only happen in two cases.  One is that LoadBalancer called {@link
   * Subchannel#shutdown} earlier, thus it should have already discarded this Subchannel.  The other
   * is that Channel is doing a {@link ManagedChannel#shutdownNow forced shutdown} or has already
   * terminated, thus there won't be further requests to LoadBalancer.  Therefore, SHUTDOWN can be
   * safely ignored.
   *
   * @param subchannel the involved Subchannel
   * @param stateInfo the new state
   * @since 1.2.0
   */
  public abstract void handleSubchannelState(
      Subchannel subchannel, ConnectivityStateInfo stateInfo);

  /**
   * The channel asks the load-balancer to shutdown.  No more callbacks will be called after this
   * method.  The implementation should shutdown all Subchannels and OOB channels, and do any other
   * cleanup as necessary.
   *
   * @since 1.2.0
   */
  public abstract void shutdown();

  /**
   * Whether this LoadBalancer can handle empty address group list to be passed to {@link
   * #handleResolvedAddresses(ResolvedAddresses)}.  The default implementation returns
   * {@code false}, meaning that if the NameResolver returns an empty list, the Channel will turn
   * that into an error and call {@link #handleNameResolutionError}.  LoadBalancers that want to
   * accept empty lists should override this method and return {@code true}.
   *
   * <p>This method should always return a constant value.  It's not specified when this will be
   * called.
   */
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return false;
  }

  /**
   * The main balancing logic.  It <strong>must be thread-safe</strong>. Typically it should only
   * synchronize on its own state, and avoid synchronizing with the LoadBalancer's state.
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class SubchannelPicker {
    /**
     * Make a balancing decision for a new RPC.
     *
     * @param args the pick arguments
     * @since 1.3.0
     */
    public abstract PickResult pickSubchannel(PickSubchannelArgs args);

    /**
     * Tries to establish connections now so that the upcoming RPC may then just pick a ready
     * connection without having to connect first.
     *
     * <p>No-op if unsupported.
     *
     * @since 1.11.0
     */
    public void requestConnection() {}
  }

  /**
   * Provides arguments for a {@link SubchannelPicker#pickSubchannel(
   * LoadBalancer.PickSubchannelArgs)}.
   *
   * @since 1.2.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class PickSubchannelArgs {

    /**
     * Call options.
     *
     * @since 1.2.0
     */
    public abstract CallOptions getCallOptions();

    /**
     * Headers of the call. {@link SubchannelPicker#pickSubchannel} may mutate it before before
     * returning.
     *
     * @since 1.2.0
     */
    public abstract Metadata getHeaders();

    /**
     * Call method.
     *
     * @since 1.2.0
     */
    public abstract MethodDescriptor<?, ?> getMethodDescriptor();
  }

  /**
   * A balancing decision made by {@link SubchannelPicker SubchannelPicker} for an RPC.
   *
   * <p>The outcome of the decision will be one of the following:
   * <ul>
   *   <li>Proceed: if a Subchannel is provided via {@link #withSubchannel withSubchannel()}, and is
   *       in READY state when the RPC tries to start on it, the RPC will proceed on that
   *       Subchannel.</li>
   *   <li>Error: if an error is provided via {@link #withError withError()}, and the RPC is not
   *       wait-for-ready (i.e., {@link CallOptions#withWaitForReady} was not called), the RPC will
   *       fail immediately with the given error.</li>
   *   <li>Buffer: in all other cases, the RPC will be buffered in the Channel, until the next
   *       picker is provided via {@link Helper#updateBalancingState Helper.updateBalancingState()},
   *       when the RPC will go through the same picking process again.</li>
   * </ul>
   *
   * @since 1.2.0
   */
  @Immutable
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public static final class PickResult {
    private static final PickResult NO_RESULT = new PickResult(null, null, Status.OK, false);

    @Nullable private final Subchannel subchannel;
    @Nullable private final ClientStreamTracer.Factory streamTracerFactory;
    // An error to be propagated to the application if subchannel == null
    // Or OK if there is no error.
    // subchannel being null and error being OK means RPC needs to wait
    private final Status status;
    // True if the result is created by withDrop()
    private final boolean drop;

    private PickResult(
        @Nullable Subchannel subchannel, @Nullable ClientStreamTracer.Factory streamTracerFactory,
        Status status, boolean drop) {
      this.subchannel = subchannel;
      this.streamTracerFactory = streamTracerFactory;
      this.status = checkNotNull(status, "status");
      this.drop = drop;
    }

    /**
     * A decision to proceed the RPC on a Subchannel.
     *
     * <p>Only Subchannels returned by {@link Helper#createSubchannel Helper.createSubchannel()}
     * will work.  DO NOT try to use your own implementations of Subchannels, as they won't work.
     *
     * <p>When the RPC tries to use the return Subchannel, which is briefly after this method
     * returns, the state of the Subchannel will decide where the RPC would go:
     *
     * <ul>
     *   <li>READY: the RPC will proceed on this Subchannel.</li>
     *   <li>IDLE: the RPC will be buffered.  Subchannel will attempt to create connection.</li>
     *   <li>All other states: the RPC will be buffered.</li>
     * </ul>
     *
     * <p><strong>All buffered RPCs will stay buffered</strong> until the next call of {@link
     * Helper#updateBalancingState Helper.updateBalancingState()}, which will trigger a new picking
     * process.
     *
     * <p>Note that Subchannel's state may change at the same time the picker is making the
     * decision, which means the decision may be made with (to-be) outdated information.  For
     * example, a picker may return a Subchannel known to be READY, but it has become IDLE when is
     * about to be used by the RPC, which makes the RPC to be buffered.  The LoadBalancer will soon
     * learn about the Subchannels' transition from READY to IDLE, create a new picker and allow the
     * RPC to use another READY transport if there is any.
     *
     * <p>You will want to avoid running into a situation where there are READY Subchannels out
     * there but some RPCs are still buffered for longer than a brief time.
     * <ul>
     *   <li>This can happen if you return Subchannels with states other than READY and IDLE.  For
     *       example, suppose you round-robin on 2 Subchannels, in READY and CONNECTING states
     *       respectively.  If the picker ignores the state and pick them equally, 50% of RPCs will
     *       be stuck in buffered state until both Subchannels are READY.</li>
     *   <li>This can also happen if you don't create a new picker at key state changes of
     *       Subchannels.  Take the above round-robin example again.  Suppose you do pick only READY
     *       and IDLE Subchannels, and initially both Subchannels are READY.  Now one becomes IDLE,
     *       then CONNECTING and stays CONNECTING for a long time.  If you don't create a new picker
     *       in response to the CONNECTING state to exclude that Subchannel, 50% of RPCs will hit it
     *       and be buffered even though the other Subchannel is READY.</li>
     * </ul>
     *
     * <p>In order to prevent unnecessary delay of RPCs, the rules of thumb are:
     * <ol>
     *   <li>The picker should only pick Subchannels that are known as READY or IDLE.  Whether to
     *       pick IDLE Subchannels depends on whether you want Subchannels to connect on-demand or
     *       actively:
     *       <ul>
     *         <li>If you want connect-on-demand, include IDLE Subchannels in your pick results,
     *             because when an RPC tries to use an IDLE Subchannel, the Subchannel will try to
     *             connect.</li>
     *         <li>If you want Subchannels to be always connected even when there is no RPC, you
     *             would call {@link Subchannel#requestConnection Subchannel.requestConnection()}
     *             whenever the Subchannel has transitioned to IDLE, then you don't need to include
     *             IDLE Subchannels in your pick results.</li>
     *       </ul></li>
     *   <li>Always create a new picker and call {@link Helper#updateBalancingState
     *       Helper.updateBalancingState()} whenever {@link #handleSubchannelState
     *       handleSubchannelState()} is called, unless the new state is SHUTDOWN. See
     *       {@code handleSubchannelState}'s javadoc for more details.</li>
     * </ol>
     *
     * @param subchannel the picked Subchannel
     * @param streamTracerFactory if not null, will be used to trace the activities of the stream
     *                            created as a result of this pick. Note it's possible that no
     *                            stream is created at all in some cases.
     * @since 1.3.0
     */
    public static PickResult withSubchannel(
        Subchannel subchannel, @Nullable ClientStreamTracer.Factory streamTracerFactory) {
      return new PickResult(
          checkNotNull(subchannel, "subchannel"), streamTracerFactory, Status.OK,
          false);
    }

    /**
     * Equivalent to {@code withSubchannel(subchannel, null)}.
     *
     * @since 1.2.0
     */
    public static PickResult withSubchannel(Subchannel subchannel) {
      return withSubchannel(subchannel, null);
    }

    /**
     * A decision to report a connectivity error to the RPC.  If the RPC is {@link
     * CallOptions#withWaitForReady wait-for-ready}, it will stay buffered.  Otherwise, it will fail
     * with the given error.
     *
     * @param error the error status.  Must not be OK.
     * @since 1.2.0
     */
    public static PickResult withError(Status error) {
      Preconditions.checkArgument(!error.isOk(), "error status shouldn't be OK");
      return new PickResult(null, null, error, false);
    }

    /**
     * A decision to fail an RPC immediately.  This is a final decision and will ignore retry
     * policy.
     *
     * @param status the status with which the RPC will fail.  Must not be OK.
     * @since 1.8.0
     */
    public static PickResult withDrop(Status status) {
      Preconditions.checkArgument(!status.isOk(), "drop status shouldn't be OK");
      return new PickResult(null, null, status, true);
    }

    /**
     * No decision could be made.  The RPC will stay buffered.
     *
     * @since 1.2.0
     */
    public static PickResult withNoResult() {
      return NO_RESULT;
    }

    /**
     * The Subchannel if this result was created by {@link #withSubchannel withSubchannel()}, or
     * null otherwise.
     *
     * @since 1.2.0
     */
    @Nullable
    public Subchannel getSubchannel() {
      return subchannel;
    }

    /**
     * The stream tracer factory this result was created with.
     *
     * @since 1.3.0
     */
    @Nullable
    public ClientStreamTracer.Factory getStreamTracerFactory() {
      return streamTracerFactory;
    }

    /**
     * The status associated with this result.  Non-{@code OK} if created with {@link #withError
     * withError}, or {@code OK} otherwise.
     *
     * @since 1.2.0
     */
    public Status getStatus() {
      return status;
    }

    /**
     * Returns {@code true} if this result was created by {@link #withDrop withDrop()}.
     *
     * @since 1.8.0
     */
    public boolean isDrop() {
      return drop;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("subchannel", subchannel)
          .add("streamTracerFactory", streamTracerFactory)
          .add("status", status)
          .add("drop", drop)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(subchannel, status, streamTracerFactory, drop);
    }

    /**
     * Returns true if the {@link Subchannel}, {@link Status}, and
     * {@link ClientStreamTracer.Factory} all match.
     */
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof PickResult)) {
        return false;
      }
      PickResult that = (PickResult) other;
      return Objects.equal(subchannel, that.subchannel) && Objects.equal(status, that.status)
          && Objects.equal(streamTracerFactory, that.streamTracerFactory)
          && drop == that.drop;
    }
  }

  /**
   * Provides essentials for LoadBalancer implementations.
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Helper {
    /**
     * Equivalent to {@link #createSubchannel(List, Attributes)} with the given single {@code
     * EquivalentAddressGroup}.
     *
     * @since 1.2.0
     */
    public final Subchannel createSubchannel(EquivalentAddressGroup addrs, Attributes attrs) {
      checkNotNull(addrs, "addrs");
      return createSubchannel(Collections.singletonList(addrs), attrs);
    }

    /**
     * Creates a Subchannel, which is a logical connection to the given group of addresses which are
     * considered equivalent.  The {@code attrs} are custom attributes associated with this
     * Subchannel, and can be accessed later through {@link Subchannel#getAttributes
     * Subchannel.getAttributes()}.
     *
     * <p>It is recommended you call this method from the Synchronization Context, otherwise your
     * logic around the creation may race with {@link #handleSubchannelState}.  See
     * <a href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for more discussions.
     *
     * <p>The LoadBalancer is responsible for closing unused Subchannels, and closing all
     * Subchannels within {@link #shutdown}.
     *
     * @throws IllegalArgumentException if {@code addrs} is empty
     * @since 1.14.0
     */
    public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {
      throw new UnsupportedOperationException();
    }

    /**
     * Equivalent to {@link #updateSubchannelAddresses(io.grpc.LoadBalancer.Subchannel, List)} with
     * the given single {@code EquivalentAddressGroup}.
     *
     * @since 1.4.0
     */
    public final void updateSubchannelAddresses(
        Subchannel subchannel, EquivalentAddressGroup addrs) {
      checkNotNull(addrs, "addrs");
      updateSubchannelAddresses(subchannel, Collections.singletonList(addrs));
    }

    /**
     * Replaces the existing addresses used with {@code subchannel}. This method is superior to
     * {@link #createSubchannel} when the new and old addresses overlap, since the subchannel can
     * continue using an existing connection.
     *
     * @throws IllegalArgumentException if {@code subchannel} was not returned from {@link
     *     #createSubchannel} or {@code addrs} is empty
     * @since 1.14.0
     */
    public void updateSubchannelAddresses(
        Subchannel subchannel, List<EquivalentAddressGroup> addrs) {
      throw new UnsupportedOperationException();
    }

    /**
     * Out-of-band channel for LoadBalancer’s own RPC needs, e.g., talking to an external
     * load-balancer service.
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     *
     * @since 1.4.0
     */
    // TODO(ejona): Allow passing a List<EAG> here and to updateOobChannelAddresses, but want to
    // wait until https://github.com/grpc/grpc-java/issues/4469 is done.
    // https://github.com/grpc/grpc-java/issues/4618
    public abstract ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority);

    /**
     * Updates the addresses used for connections in the {@code Channel} that was created by {@link
     * #createOobChannel(EquivalentAddressGroup, String)}. This is supperior to {@link
     * #createOobChannel(EquivalentAddressGroup, String)} when the old and new addresses overlap,
     * since the channel can continue using an existing connection.
     *
     * @throws IllegalArgumentException if {@code channel} was not returned from {@link
     *     #createOobChannel}
     * @since 1.4.0
     */
    public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
      throw new UnsupportedOperationException();
    }

    /**
     * Creates an out-of-band channel for LoadBalancer's own RPC needs, e.g., talking to an external
     * load-balancer service, that is specified by a target string.  See the documentation on
     * {@link ManagedChannelBuilder#forTarget} for the format of a target string.
     *
     * <p>The target string will be resolved by a {@link NameResolver} created according to the
     * target string.  The out-of-band channel doesn't have load-balancing.  If multiple addresses
     * are resolved for the target, the first working address will be used.
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     *
     * <P>NOT IMPLEMENTED: this method is currently a stub and not yet implemented by gRPC.
     *
     * @since 1.20.0
     */
    public ManagedChannel createResolvingOobChannel(String target) {
      throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Set a new state with a new picker to the channel.
     *
     * <p>When a new picker is provided via {@code updateBalancingState()}, the channel will apply
     * the picker on all buffered RPCs, by calling {@link SubchannelPicker#pickSubchannel(
     * LoadBalancer.PickSubchannelArgs)}.
     *
     * <p>The channel will hold the picker and use it for all RPCs, until {@code
     * updateBalancingState()} is called again and a new picker replaces the old one.  If {@code
     * updateBalancingState()} has never been called, the channel will buffer all RPCs until a
     * picker is provided.
     *
     * <p>The passed state will be the channel's new state. The SHUTDOWN state should not be passed
     * and its behavior is undefined.
     *
     * @since 1.6.0
     */
    public abstract void updateBalancingState(
        @Nonnull ConnectivityState newState, @Nonnull SubchannelPicker newPicker);

    /**
     * Call {@link NameResolver#refresh} on the channel's resolver.
     *
     * @since 1.18.0
     */
    public void refreshNameResolution() {
      throw new UnsupportedOperationException();
    }

    /**
     * Schedule a task to be run in the Synchronization Context, which serializes the task with the
     * callback methods on the {@link LoadBalancer} interface.
     *
     * @since 1.2.0
     * @deprecated use/implement {@code getSynchronizationContext()} instead
     */
    @Deprecated
    public void runSerialized(Runnable task) {
      getSynchronizationContext().execute(task);
    }

    /**
     * Returns a {@link SynchronizationContext} that runs tasks in the same Synchronization Context
     * as that the callback methods on the {@link LoadBalancer} interface are run in.
     *
     * <p>Pro-tip: in order to call {@link SynchronizationContext#schedule}, you need to provide a
     * {@link ScheduledExecutorService}.  {@link #getScheduledExecutorService} is provided for your
     * convenience.
     *
     * @since 1.17.0
     */
    public SynchronizationContext getSynchronizationContext() {
      // TODO(zhangkun): make getSynchronizationContext() abstract after runSerialized() is deleted
      throw new UnsupportedOperationException();
    }

    /**
     * Returns a {@link ScheduledExecutorService} for scheduling delayed tasks.
     *
     * <p>This service is a shared resource and is only meant for quick tasks.  DO NOT block or run
     * time-consuming tasks.
     *
     * <p>The returned service doesn't support {@link ScheduledExecutorService#shutdown shutdown()}
     * and {@link ScheduledExecutorService#shutdownNow shutdownNow()}.  They will throw if called.
     *
     * @since 1.17.0
     */
    public ScheduledExecutorService getScheduledExecutorService() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the NameResolver of the channel.
     *
     * @since 1.2.0
     *
     * @deprecated this method will be deleted in a future release.  If you think it shouldn't be
     *     deleted, please file an issue on <a href="https://github.com/grpc/grpc-java">github</a>.
     */
    @Deprecated
    public abstract NameResolver.Factory getNameResolverFactory();

    /**
     * Returns the authority string of the channel, which is derived from the DNS-style target name.
     *
     * @since 1.2.0
     */
    public abstract String getAuthority();

    /**
     * Returns the {@link ChannelLogger} for the Channel served by this LoadBalancer.
     *
     * @since 1.17.0
     */
    public ChannelLogger getChannelLogger() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A logical connection to a server, or a group of equivalent servers represented by an {@link 
   * EquivalentAddressGroup}.
   *
   * <p>It maintains at most one physical connection (aka transport) for sending new RPCs, while
   * also keeps track of previous transports that has been shut down but not terminated yet.
   *
   * <p>If there isn't an active transport yet, and an RPC is assigned to the Subchannel, it will
   * create a new transport.  It won't actively create transports otherwise.  {@link
   * #requestConnection requestConnection()} can be used to ask Subchannel to create a transport if
   * there isn't any.
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Subchannel {
    /**
     * Shuts down the Subchannel.  After this method is called, this Subchannel should no longer
     * be returned by the latest {@link SubchannelPicker picker}, and can be safely discarded.
     *
     * @since 1.2.0
     */
    public abstract void shutdown();

    /**
     * Asks the Subchannel to create a connection (aka transport), if there isn't an active one.
     *
     * @since 1.2.0
     */
    public abstract void requestConnection();

    /**
     * Returns the addresses that this Subchannel is bound to.  This can be called only if
     * the Subchannel has only one {@link EquivalentAddressGroup}.  Under the hood it calls
     * {@link #getAllAddresses}.
     *
     * @throws IllegalStateException if this subchannel has more than one EquivalentAddressGroup.
     *         Use {@link #getAllAddresses} instead
     * @since 1.2.0
     */
    public final EquivalentAddressGroup getAddresses() {
      List<EquivalentAddressGroup> groups = getAllAddresses();
      Preconditions.checkState(groups.size() == 1, "Does not have exactly one group");
      return groups.get(0);
    }

    /**
     * Returns the addresses that this Subchannel is bound to. The returned list will not be empty.
     *
     * @since 1.14.0
     */
    public List<EquivalentAddressGroup> getAllAddresses() {
      throw new UnsupportedOperationException();
    }

    /**
     * The same attributes passed to {@link Helper#createSubchannel Helper.createSubchannel()}.
     * LoadBalancer can use it to attach additional information here, e.g., the shard this
     * Subchannel belongs to.
     *
     * @since 1.2.0
     */
    public abstract Attributes getAttributes();

    /**
     * (Internal use only) returns a {@link Channel} that is backed by this Subchannel.  This allows
     * a LoadBalancer to issue its own RPCs for auxiliary purposes, such as health-checking, on
     * already-established connections.  This channel has certain restrictions:
     * <ol>
     *   <li>It can issue RPCs only if the Subchannel is {@code READY}. If {@link
     *   Channel#newCall} is called when the Subchannel is not {@code READY}, the RPC will fail
     *   immediately.</li>
     *   <li>It doesn't support {@link CallOptions#withWaitForReady wait-for-ready} RPCs. Such RPCs
     *   will fail immediately.</li>
     * </ol>
     *
     * <p>RPCs made on this Channel is not counted when determining ManagedChannel's {@link
     * ManagedChannelBuilder#idleTimeout idle mode}.  In other words, they won't prevent
     * ManagedChannel from entering idle mode.
     *
     * <p>Warning: RPCs made on this channel will prevent a shut-down transport from terminating. If
     * you make long-running RPCs, you need to make sure they will finish in time after the
     * Subchannel has transitioned away from {@code READY} state
     * (notified through {@link #handleSubchannelState}).
     *
     * <p>Warning: this is INTERNAL API, is not supposed to be used by external users, and may
     * change without notice. If you think you must use it, please file an issue.
     */
    @Internal
    public Channel asChannel() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns a {@link ChannelLogger} for this Subchannel.
     *
     * @since 1.17.0
     */
    public ChannelLogger getChannelLogger() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Factory to create {@link LoadBalancer} instance.
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Factory {
    /**
     * Creates a {@link LoadBalancer} that will be used inside a channel.
     *
     * @since 1.2.0
     */
    public abstract LoadBalancer newLoadBalancer(Helper helper);
  }
}
