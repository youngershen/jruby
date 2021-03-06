/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2007 Ola Bini <ola@ologix.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ext.socket;

import jnr.constants.platform.AddressFamily;
import jnr.constants.platform.INAddr;
import jnr.constants.platform.IPProto;
import jnr.constants.platform.NameInfo;
import jnr.constants.platform.ProtocolFamily;
import jnr.constants.platform.Shutdown;
import jnr.constants.platform.Sock;
import jnr.constants.platform.SocketLevel;
import jnr.constants.platform.SocketOption;
import jnr.constants.platform.TCP;
import org.jruby.CompatVersion;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyModule;
import org.jruby.RubyNumeric;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.io.ChannelDescriptor;
import org.jruby.util.io.ModeFlags;
import org.jruby.util.io.Sockaddr;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.regex.Pattern;

/**
 * @author <a href="mailto:ola.bini@ki.se">Ola Bini</a>
 */
@JRubyClass(name="Socket", parent="BasicSocket", include="Socket::Constants")
public class RubySocket extends RubyBasicSocket {
    static void createSocket(Ruby runtime) {
        RubyClass rb_cSocket = runtime.defineClass("Socket", runtime.getClass("BasicSocket"), SOCKET_ALLOCATOR);

        RubyModule rb_mConstants = rb_cSocket.defineModuleUnder("Constants");
        // we don't have to define any that we don't support; see socket.c

        runtime.loadConstantSet(rb_mConstants, Sock.class);
        runtime.loadConstantSet(rb_mConstants, SocketOption.class);
        runtime.loadConstantSet(rb_mConstants, SocketLevel.class);
        runtime.loadConstantSet(rb_mConstants, ProtocolFamily.class);
        runtime.loadConstantSet(rb_mConstants, AddressFamily.class);
        runtime.loadConstantSet(rb_mConstants, INAddr.class);
        runtime.loadConstantSet(rb_mConstants, IPProto.class);
        runtime.loadConstantSet(rb_mConstants, Shutdown.class);
        runtime.loadConstantSet(rb_mConstants, TCP.class);
        runtime.loadConstantSet(rb_mConstants, NameInfo.class);

        // mandatory constants we haven't implemented
        rb_mConstants.setConstant("MSG_OOB", runtime.newFixnum(MSG_OOB));
        rb_mConstants.setConstant("MSG_PEEK", runtime.newFixnum(MSG_PEEK));
        rb_mConstants.setConstant("MSG_DONTROUTE", runtime.newFixnum(MSG_DONTROUTE));
        rb_mConstants.setConstant("MSG_WAITALL", runtime.newFixnum(MSG_WAITALL));

        // constants webrick crashes without
        rb_mConstants.setConstant("AI_PASSIVE", runtime.newFixnum(1));

        // More constants needed by specs
        rb_mConstants.setConstant("IP_MULTICAST_TTL", runtime.newFixnum(10));
        rb_mConstants.setConstant("IP_MULTICAST_LOOP", runtime.newFixnum(11));
        rb_mConstants.setConstant("IP_ADD_MEMBERSHIP", runtime.newFixnum(12));
        rb_mConstants.setConstant("IP_MAX_MEMBERSHIPS", runtime.newFixnum(20));
        rb_mConstants.setConstant("IP_DEFAULT_MULTICAST_LOOP", runtime.newFixnum(1));
        rb_mConstants.setConstant("IP_DEFAULT_MULTICAST_TTL", runtime.newFixnum(1));

        rb_cSocket.includeModule(rb_mConstants);

        rb_cSocket.defineAnnotatedMethods(RubySocket.class);
        rb_cSocket.defineAnnotatedMethods(SocketUtils.class);
    }

    private static ObjectAllocator SOCKET_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubySocket(runtime, klass);
        }
    };

    public RubySocket(Ruby runtime, RubyClass type) {
        super(runtime, type);
    }

    @JRubyMethod(meta = true)
    public static IRubyObject for_fd(ThreadContext context, IRubyObject socketClass, IRubyObject fd) {
        Ruby runtime = context.getRuntime();

        if (fd instanceof RubyFixnum) {
            int intFD = (int)((RubyFixnum)fd).getLongValue();

            ChannelDescriptor descriptor = ChannelDescriptor.getDescriptorByFileno(intFD);

            if (descriptor == null) {
                throw runtime.newErrnoEBADFError();
            }

            RubySocket socket = (RubySocket)((RubyClass)socketClass).allocate();

            socket.initFieldsFromDescriptor(runtime, descriptor);

            socket.initSocket(runtime, descriptor);

            return socket;
        } else {
            throw runtime.newTypeError(fd, context.getRuntime().getFixnum());
        }
    }

    @JRubyMethod(compat = CompatVersion.RUBY1_8)
    public IRubyObject initialize(ThreadContext context, IRubyObject domain, IRubyObject type, IRubyObject protocol) {
        Ruby runtime = context.runtime;

        initFieldsFromArgs(runtime, domain, type, protocol);

        ChannelDescriptor descriptor = initChannel(runtime);

        initSocket(runtime, descriptor);

        return this;
    }

    @JRubyMethod(name = "initialize", compat = CompatVersion.RUBY1_9)
    public IRubyObject initialize19(ThreadContext context, IRubyObject domain, IRubyObject type) {
        Ruby runtime = context.runtime;

        initFieldsFromArgs(runtime, domain, type);

        ChannelDescriptor descriptor = initChannel(runtime);

        initSocket(runtime, descriptor);

        return this;
    }

    @JRubyMethod(name = "initialize", compat = CompatVersion.RUBY1_9)
    public IRubyObject initialize19(ThreadContext context, IRubyObject domain, IRubyObject type, IRubyObject protocol) {
        Ruby runtime = context.runtime;

        initFieldsFromArgs(runtime, domain, type, protocol);

        ChannelDescriptor descriptor = initChannel(runtime);

        initSocket(runtime, descriptor);

        return this;
    }

    @JRubyMethod()
    public IRubyObject connect_nonblock(ThreadContext context, IRubyObject arg) {
        InetSocketAddress iaddr = Sockaddr.addressFromSockaddr_in(context, arg);

        doConnectNonblock(context, getChannel(), iaddr);

        return RubyFixnum.zero(context.runtime);
    }

    @JRubyMethod()
    public IRubyObject connect(ThreadContext context, IRubyObject arg) {
        InetSocketAddress iaddr = Sockaddr.addressFromSockaddr_in(context, arg);

        doConnect(context, getChannel(), iaddr);

        return RubyFixnum.zero(context.runtime);
    }

    @JRubyMethod()
    public IRubyObject bind(ThreadContext context, IRubyObject arg) {
        InetSocketAddress iaddr = Sockaddr.addressFromSockaddr_in(context, arg);

        doBind(context, getChannel(), iaddr);

        return RubyFixnum.zero(context.getRuntime());
    }

    @JRubyMethod(notImplemented = true)
    public IRubyObject listen(ThreadContext context, IRubyObject backlog) {
        throw sockerr(context.runtime, JRUBY_SERVER_SOCKET_ERROR);
    }

    @JRubyMethod(notImplemented = true)
    public IRubyObject accept(ThreadContext context) {
        throw sockerr(context.runtime, JRUBY_SERVER_SOCKET_ERROR);
    }

    @Override
    protected Sock getDefaultSocketType() {
        return soType;
    }

    private void initFieldsFromDescriptor(Ruby runtime, ChannelDescriptor descriptor) {
        Channel mainChannel = descriptor.getChannel();

        if (mainChannel instanceof SocketChannel) {
            // ok, it's a socket...set values accordingly
            // just using AF_INET since we can't tell from SocketChannel...
            soDomain = AddressFamily.AF_INET;
            soType = Sock.SOCK_STREAM;
            soProtocol = ProtocolFamily.PF_INET;

        } else if (mainChannel instanceof DatagramChannel) {
            // datagram, set accordingly
            // again, AF_INET
            soDomain = AddressFamily.AF_INET;
            soType = Sock.SOCK_DGRAM;
            soProtocol = ProtocolFamily.PF_INET;

        } else {
            throw runtime.newErrnoENOTSOCKError("can't Socket.new/for_fd against a non-socket");
        }
    }

    private void initFieldsFromArgs(Ruby runtime, IRubyObject domain, IRubyObject type, IRubyObject protocol) {
        initDomain(runtime, domain);

        initType(runtime, type);

        initProtocol(runtime, protocol);
    }

    private void initFieldsFromArgs(Ruby runtime, IRubyObject domain, IRubyObject type) {
        initDomain(runtime, domain);

        initType(runtime, type);
    }

    protected void initFromServer(Ruby runtime, RubyServerSocket serverSocket, SocketChannel socketChannel) {
        soDomain = serverSocket.soDomain;
        soType = serverSocket.soType;
        soProtocol = serverSocket.soProtocol;

        initSocket(runtime, newChannelDescriptor(runtime, socketChannel));
    }

    protected ChannelDescriptor initChannel(Ruby runtime) {
        Channel channel;

        try {
            if(soType == Sock.SOCK_STREAM) {
                channel = SocketChannel.open();

            } else if(soType == Sock.SOCK_DGRAM) {
                channel = DatagramChannel.open();

            } else {
                throw runtime.newArgumentError("unsupported socket type `" + soType + "'");

            }

            return newChannelDescriptor(runtime, channel);

        } catch(IOException e) {
            throw sockerr(runtime, "initialize: " + e.toString());

        }
    }

    protected static ChannelDescriptor newChannelDescriptor(Ruby runtime, Channel channel) {
        ModeFlags modeFlags = newModeFlags(runtime, ModeFlags.RDWR);

        return new ChannelDescriptor(channel, modeFlags);
    }

    private void initProtocol(Ruby runtime, IRubyObject protocol) {
        ProtocolFamily protocolFamily = null;
        
        if(protocol instanceof RubyString || protocol instanceof RubySymbol) {
            String protocolString = protocol.toString();
            protocolFamily = ProtocolFamily.valueOf("PF_" + protocolString);
        } else {
            int protocolInt = RubyNumeric.fix2int(protocol);
            protocolFamily = ProtocolFamily.valueOf(protocolInt);
        }

        if (protocolFamily == null) {
            throw sockerr(runtime, "unknown socket protocol " + protocol);
        }

        soProtocol = protocolFamily;
    }

    private void initType(Ruby runtime, IRubyObject type) {
        Sock sockType = null;

        if(type instanceof RubyString || type instanceof RubySymbol) {
            String typeString = type.toString();
            sockType = Sock.valueOf("SOCK_" + typeString);
        } else {
            int typeInt = RubyNumeric.fix2int(type);
            sockType = Sock.valueOf(typeInt);
        }

        if (sockType == null) {
            throw sockerr(runtime, "unknown socket type " + type);
        }

        soType = sockType;
    }

    private void initDomain(Ruby runtime, IRubyObject domain) {
        AddressFamily addressFamily = null;

        if(domain instanceof RubyString || domain instanceof RubySymbol) {
            String domainString = domain.toString();
            addressFamily = AddressFamily.valueOf("AF_" + domainString);
        } else {
            int domainInt = RubyNumeric.fix2int(domain);
            addressFamily = AddressFamily.valueOf(domainInt);
        }

        if (addressFamily == null) {
            throw sockerr(runtime, "unknown socket domain " + domain);
        }

        soDomain = addressFamily;
    }

    private void doConnectNonblock(ThreadContext context, Channel channel, InetSocketAddress iaddr) {
        try {
            if (channel instanceof SelectableChannel) {
                SelectableChannel selectable = (SelectableChannel)channel;
                selectable.configureBlocking(false);

                doConnect(context, channel, iaddr);
            } else {
                throw getRuntime().newErrnoENOPROTOOPTError();

            }

        } catch(ClosedChannelException e) {
            throw context.getRuntime().newErrnoECONNREFUSEDError();

        } catch(IOException e) {
            throw sockerr(context.getRuntime(), "connect(2): name or service not known");

        }
    }

    protected void doConnect(ThreadContext context, Channel channel, InetSocketAddress iaddr) {
        Ruby runtime = context.runtime;

        try {
            if (channel instanceof SocketChannel) {
                SocketChannel socket = (SocketChannel)channel;

                if(!socket.connect(iaddr)) {
                    if (runtime.is1_9()) {
                        throw runtime.newErrnoEINPROGRESSWritableError();
                    } else {
                        throw runtime.newErrnoEINPROGRESSError();
                    }
                }

            } else if (channel instanceof DatagramChannel) {
                ((DatagramChannel)channel).connect(iaddr);

            } else {
                throw runtime.newErrnoENOPROTOOPTError();

            }

        } catch(AlreadyConnectedException e) {
            throw runtime.newErrnoEISCONNError();

        } catch(ConnectionPendingException e) {
            throw runtime.newErrnoEINPROGRESSError();

        } catch(UnknownHostException e) {
            throw sockerr(runtime, "connect(2): unknown host");

        } catch(SocketException e) {
            handleSocketException(runtime, "connect", e);

        } catch(IOException e) {
            throw sockerr(runtime, "connect(2): name or service not known");

        } catch (IllegalArgumentException iae) {
            throw sockerr(runtime, iae.getMessage());

        }
    }

    protected void doBind(ThreadContext context, Channel channel, InetSocketAddress iaddr) {
        Ruby runtime = context.runtime;

        try {
            if (channel instanceof SocketChannel) {
                Socket socket = ((SocketChannel)channel).socket();
                socket.bind(iaddr);

            } else if (channel instanceof DatagramChannel) {
                DatagramSocket socket = ((DatagramChannel)channel).socket();
                socket.bind(iaddr);

            } else {
                throw runtime.newErrnoENOPROTOOPTError();
            }

        } catch(UnknownHostException e) {
            throw sockerr(runtime, "bind(2): unknown host");

        } catch(SocketException e) {
            handleSocketException(runtime, "bind", e);

        } catch(IOException e) {
            throw sockerr(runtime, "bind(2): name or service not known");

        } catch (IllegalArgumentException iae) {
            throw sockerr(runtime, iae.getMessage());

        }
    }

    protected void handleSocketException(Ruby runtime, String caller, SocketException e) {
        String msg = formatMessage(e, "bind");

        // This is ugly, but what can we do, Java provides the same exception type
        // for different situations, so we differentiate the errors
        // based on the exception's message.
        if (ALREADY_BOUND_PATTERN.matcher(msg).find()) {
            throw runtime.newErrnoEINVALError(msg);
        } else if (ADDR_NOT_AVAIL_PATTERN.matcher(msg).find()) {
            throw runtime.newErrnoEADDRNOTAVAILError(msg);
        } else if (PERM_DENIED_PATTERN.matcher(msg).find()) {
            throw runtime.newErrnoEACCESError(msg);
        } else {
            throw runtime.newErrnoEADDRINUSEError(msg);
        }
    }

    private static String formatMessage(Throwable e, String defaultMsg) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = defaultMsg;
        } else {
            msg = defaultMsg + " - " + msg;
        }
        return msg;
    }

    public static RuntimeException sockerr(Ruby runtime, String msg) {
        return new RaiseException(runtime, runtime.getClass("SocketError"), msg, true);
    }

    @Deprecated
    public static IRubyObject gethostname(ThreadContext context, IRubyObject recv) {
        return SocketUtils.gethostname(context, recv);
    }

    @Deprecated
    public static IRubyObject gethostbyaddr(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        return SocketUtils.gethostbyaddr(context, recv, args);
    }

    @Deprecated
    public static IRubyObject getservbyname(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        return SocketUtils.getservbyname(context, recv, args);
    }

    @Deprecated
    public static IRubyObject pack_sockaddr_un(ThreadContext context, IRubyObject recv, IRubyObject filename) {
        return SocketUtils.pack_sockaddr_un(context, recv, filename);
    }

    @Deprecated
    public static IRubyObject pack_sockaddr_in(ThreadContext context, IRubyObject recv, IRubyObject port, IRubyObject host) {
        return SocketUtils.pack_sockaddr_in(context, recv, port, host);
    }

    @Deprecated
    public static IRubyObject unpack_sockaddr_in(ThreadContext context, IRubyObject recv, IRubyObject addr) {
        return SocketUtils.unpack_sockaddr_in(context, recv, addr);
    }

    @Deprecated
    public static IRubyObject gethostbyname(ThreadContext context, IRubyObject recv, IRubyObject hostname) {
        return SocketUtils.gethostbyname(context, recv, hostname);
    }

    @Deprecated
    public static IRubyObject getaddrinfo(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        return SocketUtils.getaddrinfo(context, recv, args);
    }

    @Deprecated
    public static IRubyObject getnameinfo(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        return SocketUtils.getnameinfo(context, recv, args);
    }

    @Deprecated
    public static InetAddress getRubyInetAddress(ByteList address) throws UnknownHostException {
        return SocketUtils.getRubyInetAddress(address);
    }

    private static final Pattern ALREADY_BOUND_PATTERN = Pattern.compile("[Aa]lready.*bound");
    private static final Pattern ADDR_NOT_AVAIL_PATTERN = Pattern.compile("assign.*address");
    private static final Pattern PERM_DENIED_PATTERN = Pattern.compile("[Pp]ermission.*denied");

    public static final int MSG_OOB = 0x1;
    public static final int MSG_PEEK = 0x2;
    public static final int MSG_DONTROUTE = 0x4;
    public static final int MSG_WAITALL = 0x100;

    protected AddressFamily soDomain;
    protected Sock soType;
    protected ProtocolFamily soProtocol;

    private static final String JRUBY_SERVER_SOCKET_ERROR =
            "use ServerSocket for servers (http://wiki.jruby.org/ServerSocket)";
}// RubySocket
