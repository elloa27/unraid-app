//go:build android

package tsnetbridge

/*
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <string.h>

static int ts_sa_family(struct sockaddr *sa) {
	if (sa == NULL) return -1;
	return sa->sa_family;
}

static int ts_copy_ipv4(struct sockaddr *sa, unsigned char *dst) {
	if (sa == NULL || sa->sa_family != AF_INET) return 0;
	struct sockaddr_in *sin = (struct sockaddr_in *)sa;
	memcpy(dst, &sin->sin_addr, 4);
	return 1;
}

static int ts_copy_ipv6(struct sockaddr *sa, unsigned char *dst) {
	if (sa == NULL || sa->sa_family != AF_INET6) return 0;
	struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)sa;
	memcpy(dst, &sin6->sin6_addr, 16);
	return 1;
}
*/
import "C"

import (
	"net"
	"unsafe"

	"tailscale.com/net/netmon"
)

// Android 11(API 30) 이상에서 일반 앱의 netlink 소켓 제한(netlinkrib: permission denied)을
// 회피하기 위해 Android bionic 표준 getifaddrs(3) + UDP 로컬 IP 프로브 폴백을 등록합니다.
func init() {
	netmon.RegisterInterfaceGetter(androidGetInterfaces)
}

func androidGetInterfaces() ([]netmon.Interface, error) {
	ifaces, err := getInterfacesViaBionic()
	if err == nil && hasNonLoopbackAddr(ifaces) {
		return ifaces, nil
	}

	// 일부 엄격한 Android SELinux 환경에서 getifaddrs가 제한된 경우
	// 커널 라우팅 테이블에 패킷 전송 없이 UDP 소켓 바인딩으로 활성 로컬 IP를 안전하게 조회
	return buildSyntheticFallbackInterfaces(), nil
}

func getInterfacesViaBionic() ([]netmon.Interface, error) {
	var ifap *C.struct_ifaddrs
	if rc := C.getifaddrs(&ifap); rc != 0 {
		return nil, net.InvalidAddrError("getifaddrs failed")
	}
	if ifap == nil {
		return nil, nil
	}
	defer C.freeifaddrs(ifap)

	type ifaceEntry struct {
		iface net.Interface
		addrs []net.Addr
	}

	entries := make([]*ifaceEntry, 0, 8)
	byName := make(map[string]*ifaceEntry, 8)

	for cur := ifap; cur != nil; cur = cur.ifa_next {
		if cur.ifa_name == nil {
			continue
		}
		name := C.GoString(cur.ifa_name)
		entry, exists := byName[name]
		if !exists {
			idx := int(C.if_nametoindex(cur.ifa_name))
			if idx <= 0 {
				idx = len(entries) + 1
			}
			entry = &ifaceEntry{
				iface: net.Interface{
					Index: idx,
					MTU:   1500,
					Name:  name,
					Flags: convertAndroidFlags(uint(cur.ifa_flags)),
				},
			}
			byName[name] = entry
			entries = append(entries, entry)
		}

		if cur.ifa_addr == nil {
			continue
		}

		switch C.ts_sa_family(cur.ifa_addr) {
		case C.AF_INET:
			var ipBuf [4]byte
			if C.ts_copy_ipv4(cur.ifa_addr, (*C.uchar)(unsafe.Pointer(&ipBuf[0]))) == 1 {
				ip := net.IPv4(ipBuf[0], ipBuf[1], ipBuf[2], ipBuf[3]).To4()
				mask := net.CIDRMask(32, 32)
				var maskBuf [4]byte
				if cur.ifa_netmask != nil &&
					C.ts_copy_ipv4(cur.ifa_netmask, (*C.uchar)(unsafe.Pointer(&maskBuf[0]))) == 1 {
					mask = net.IPv4Mask(maskBuf[0], maskBuf[1], maskBuf[2], maskBuf[3])
				}
				entry.addrs = append(entry.addrs, &net.IPNet{IP: ip, Mask: mask})
			}

		case C.AF_INET6:
			var ipBuf [16]byte
			if C.ts_copy_ipv6(cur.ifa_addr, (*C.uchar)(unsafe.Pointer(&ipBuf[0]))) == 1 {
				ip := make(net.IP, 16)
				copy(ip, ipBuf[:])
				mask := net.CIDRMask(128, 128)
				var maskBuf [16]byte
				if cur.ifa_netmask != nil &&
					C.ts_copy_ipv6(cur.ifa_netmask, (*C.uchar)(unsafe.Pointer(&maskBuf[0]))) == 1 {
					m := make(net.IPMask, 16)
					copy(m, maskBuf[:])
					mask = m
				}
				entry.addrs = append(entry.addrs, &net.IPNet{IP: ip, Mask: mask})
			}
		}
	}

	result := make([]netmon.Interface, 0, len(entries))
	for _, e := range entries {
		ni := e.iface
		result = append(result, netmon.Interface{
			Interface: &ni,
			AltAddrs:  e.addrs,
		})
	}
	return result, nil
}

func hasNonLoopbackAddr(ifaces []netmon.Interface) bool {
	for _, iface := range ifaces {
		if iface.Interface == nil || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		if len(iface.AltAddrs) > 0 {
			return true
		}
	}
	return false
}

func buildSyntheticFallbackInterfaces() []netmon.Interface {
	loIface := net.Interface{
		Index: 1,
		MTU:   65536,
		Name:  "lo",
		Flags: net.FlagUp | net.FlagLoopback | net.FlagRunning,
	}
	result := []netmon.Interface{
		{
			Interface: &loIface,
			AltAddrs: []net.Addr{
				&net.IPNet{IP: net.IPv4(127, 0, 0, 1), Mask: net.CIDRMask(8, 32)},
			},
		},
	}

	// 패킷을 전송하지 않고 활성 아웃바운드 로컬 IP 확인
	var activeAddrs []net.Addr
	if conn, err := net.Dial("udp4", "1.1.1.1:53"); err == nil {
		if udpAddr, ok := conn.LocalAddr().(*net.UDPAddr); ok && udpAddr.IP != nil {
			if ip4 := udpAddr.IP.To4(); ip4 != nil {
				activeAddrs = append(activeAddrs, &net.IPNet{
					IP:   ip4,
					Mask: net.CIDRMask(24, 32),
				})
			}
		}
		_ = conn.Close()
	}

	if len(activeAddrs) > 0 {
		netIface := net.Interface{
			Index: 2,
			MTU:   1500,
			Name:  "android0",
			Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast | net.FlagRunning,
		}
		result = append(result, netmon.Interface{
			Interface: &netIface,
			AltAddrs:  activeAddrs,
		})
	}
	return result
}

func convertAndroidFlags(raw uint) net.Flags {
	var f net.Flags
	if raw&uint(C.IFF_UP) != 0 {
		f |= net.FlagUp
	}
	if raw&uint(C.IFF_BROADCAST) != 0 {
		f |= net.FlagBroadcast
	}
	if raw&uint(C.IFF_LOOPBACK) != 0 {
		f |= net.FlagLoopback
	}
	if raw&uint(C.IFF_POINTOPOINT) != 0 {
		f |= net.FlagPointToPoint
	}
	if raw&uint(C.IFF_MULTICAST) != 0 {
		f |= net.FlagMulticast
	}
	if raw&uint(C.IFF_RUNNING) != 0 {
		f |= net.FlagRunning
	}
	return f
}
