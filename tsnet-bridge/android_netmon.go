package tsnetbridge

import (
	"fmt"

	"github.com/wlynxg/anet"
	"tailscale.com/net/netmon"
)

// Android 11(API 30) 이상에서 일반 앱의 netlink 소켓 접근 제한(netlinkrib: permission denied)을
// 회피하기 위해 anet 기반 인터페이스 조회 함수를 Tailscale netmon에 등록합니다.
func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := anet.Interfaces()
		if err != nil {
			return nil, fmt.Errorf("anet.Interfaces 조회 실패: %w", err)
		}

		result := make([]netmon.Interface, 0, len(ifs))
		for i := range ifs {
			iface := ifs[i]
			addrs, addrErr := anet.InterfaceAddrsByInterface(&iface)
			if addrErr != nil {
				// 개별 인터페이스 주소 조회 실패 시 전체 초기화를 중단하지 않고 빈 주소로 진행
				addrs = nil
			}
			result = append(result, netmon.Interface{
				Interface: &iface,
				AltAddrs:  addrs,
			})
		}
		return result, nil
	})
}
