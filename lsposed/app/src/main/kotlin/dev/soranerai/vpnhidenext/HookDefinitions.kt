package dev.soranerai.vpnhidenext

data class HookInfo(
    val index: Int,
    val nameRes: Int,
    val symbolRes: Int,
    val descriptionRes: Int,
    val indices: List<Int>? = null,
    val daemonControlled: Boolean = false,
) {
    val allIndices: List<Int>
        get() = indices ?: listOf(index)
}

val ALL_HOOKS =
    listOf(
        HookInfo(
            0,
            R.string.hook_name_dev_ioctl,
            R.string.hook_symbol_dev_ioctl,
            R.string.hook_desc_dev_ioctl,
        ),
        HookInfo(
            1,
            R.string.hook_name_sock_ioctl,
            R.string.hook_symbol_sock_ioctl,
            R.string.hook_desc_sock_ioctl,
        ),
        HookInfo(
            2,
            R.string.hook_name_rtnl_fill_ifinfo,
            R.string.hook_symbol_rtnl_fill_ifinfo,
            R.string.hook_desc_rtnl_fill_ifinfo,
        ),
        HookInfo(
            3,
            R.string.hook_name_inet6_fill_ifaddr,
            R.string.hook_symbol_inet6_fill_ifaddr,
            R.string.hook_desc_inet6_fill_ifaddr,
        ),
        HookInfo(
            4,
            R.string.hook_name_inet_fill_ifaddr,
            R.string.hook_symbol_inet_fill_ifaddr,
            R.string.hook_desc_inet_fill_ifaddr,
        ),
        HookInfo(
            5,
            R.string.hook_name_fib_route_seq_show,
            R.string.hook_symbol_fib_route_seq_show,
            R.string.hook_desc_fib_route_seq_show,
        ),
        HookInfo(
            6,
            R.string.hook_name_ipv6_route_seq_show,
            R.string.hook_symbol_ipv6_route_seq_show,
            R.string.hook_desc_ipv6_route_seq_show,
        ),
        HookInfo(
            7,
            R.string.hook_name_fib_dump_info,
            R.string.hook_symbol_fib_dump_info,
            R.string.hook_desc_fib_dump_info,
        ),
        HookInfo(
            8,
            R.string.hook_name_fib_nl_fill_rule,
            R.string.hook_symbol_fib_nl_fill_rule,
            R.string.hook_desc_fib_nl_fill_rule,
        ),
        HookInfo(
            9,
            R.string.hook_name_rt6_fill_node,
            R.string.hook_symbol_rt6_fill_node,
            R.string.hook_desc_rt6_fill_node,
        ),
        HookInfo(
            10,
            R.string.hook_name_rt_fill_info,
            R.string.hook_symbol_rt_fill_info,
            R.string.hook_desc_rt_fill_info,
        ),
        HookInfo(
            11,
            R.string.hook_name_sys_setsockopt,
            R.string.hook_symbol_sys_setsockopt,
            R.string.hook_desc_sys_setsockopt,
        ),
        HookInfo(
            12,
            R.string.hook_name_sys_getsockopt,
            R.string.hook_symbol_sys_getsockopt,
            R.string.hook_desc_sys_getsockopt,
        ),
        // HookInfo(
        //     13,
        //     R.string.hook_name_sys_connect,
        //     R.string.hook_symbol_sys_connect,
        //     R.string.hook_desc_sys_connect,
        // ),
        HookInfo(
            14,
            R.string.hook_name_sys_getsockname_ipv4,
            R.string.hook_symbol_sys_getsockname_ipv4,
            R.string.hook_desc_sys_getsockname_ipv4,
        ),
        HookInfo(
            15,
            R.string.hook_name_sys_getsockname_ipv6,
            R.string.hook_symbol_sys_getsockname_ipv6,
            R.string.hook_desc_sys_getsockname_ipv6,
        ),
        // HookInfo(
        //     16,
        //     R.string.hook_name_sys_bind,
        //     R.string.hook_symbol_sys_bind,
        //     R.string.hook_desc_sys_bind,
        // ),
        HookInfo(
            17,
            R.string.hook_name_bpf_stats_spoof,
            R.string.hook_symbol_bpf_stats_spoof,
            R.string.hook_desc_bpf_stats_spoof,
        ),
        HookInfo(
            18,
            R.string.hook_name_susfs_path_hiding,
            R.string.hook_symbol_susfs_path_hiding,
            R.string.hook_desc_susfs_path_hiding,
            daemonControlled = true,
        ),
        HookInfo(
            26,
            R.string.hook_name_dev_seq_show,
            R.string.hook_symbol_dev_seq_show,
            R.string.hook_desc_dev_seq_show,
        ),
        HookInfo(
            27,
            R.string.hook_name_if6_seq_show,
            R.string.hook_symbol_if6_seq_show,
            R.string.hook_desc_if6_seq_show,
        ),
        HookInfo(
            25,
            R.string.hook_name_udp_sendmsg,
            R.string.hook_symbol_udp_sendmsg,
            R.string.hook_desc_udp_sendmsg,
        ),
        HookInfo(
            28,
            R.string.hook_name_inet6_bind_ll,
            R.string.hook_symbol_inet6_bind_ll,
            R.string.hook_desc_inet6_bind_ll,
        ),
        HookInfo(
            29,
            R.string.hook_name_udpv6_sendmsg_ll,
            R.string.hook_symbol_udpv6_sendmsg_ll,
            R.string.hook_desc_udpv6_sendmsg_ll,
        ),
        HookInfo(
            30,
            R.string.hook_name_fib_trie_seq_show,
            R.string.hook_symbol_fib_trie_seq_show,
            R.string.hook_desc_fib_trie_seq_show,
        ),
        HookInfo(
            31,
            R.string.hook_name_tc_fill_qdisc,
            R.string.hook_symbol_tc_fill_qdisc,
            R.string.hook_desc_tc_fill_qdisc,
        ),
    )

val ALL_JAVA_HOOKS =
    listOf(
        HookInfo(
            0,
            R.string.hook_name_link_properties,
            R.string.hook_symbol_link_properties,
            R.string.hook_desc_link_properties,
        ),
        HookInfo(
            1,
            R.string.hook_name_network_capabilities,
            R.string.hook_symbol_network_capabilities,
            R.string.hook_desc_network_capabilities,
        ),
        HookInfo(
            2,
            R.string.hook_name_network_info,
            R.string.hook_symbol_network_info,
            R.string.hook_desc_network_info,
        ),
        HookInfo(
            3,
            R.string.hook_name_network,
            R.string.hook_symbol_network,
            R.string.hook_desc_network,
        ),
        HookInfo(
            4,
            R.string.hook_name_connectivity_service,
            R.string.hook_symbol_connectivity_service,
            R.string.hook_desc_connectivity_service,
        ),
        HookInfo(
            5,
            R.string.hook_name_package_manager,
            R.string.hook_symbol_package_manager,
            R.string.hook_desc_package_manager,
        ),
        HookInfo(
            6,
            R.string.hook_name_user_manager,
            R.string.hook_symbol_user_manager,
            R.string.hook_desc_user_manager,
        ),
        HookInfo(
            7,
            R.string.hook_name_self_hide,
            R.string.hook_symbol_self_hide,
            R.string.hook_desc_self_hide,
        ),
    )
