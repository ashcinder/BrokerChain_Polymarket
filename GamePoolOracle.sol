// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract PredictionMarket {
    address public officialOracle;

    struct Game {
        uint256 id;
        string description;
        string condition;
        string avatarUrl;
        string detailedInfo;
        string[] optionNames;
        uint8 optionCount;

        // AMM 升级核心数据结构
        uint256 totalLiquidity;                    // 真实吸纳的总资金池 (BKC)
        mapping(uint8 => uint256) virtualReserves; // AMM 虚拟储备池：用于计算动态价格
        mapping(uint8 => uint256) optionTotalShares; // 条件代币：记录每个选项总共发行了多少份额

        bool isResolved;
        uint8 winningOption;
        uint256 deadline;
        bool isRefunded;
    }

    uint256 public gameCount;
    mapping(uint256 => Game) public games;

    // 用户持有的不再是投入的金额，而是“条件代币份额 (Shares)”
    mapping(uint256 => mapping(address => mapping(uint8 => uint256))) public userShares;

    constructor() {
        officialOracle = msg.sender;
    }

    // 1. 发行市场，并初始化 AMM 虚拟流动性池
    function createGame(
        string memory _desc,
        string memory _condition,
        string memory _avatarUrl,
        string memory _detailedInfo,
        string[] memory _optionNames,
        uint256 _duration
    ) public {
        require(_optionNames.length >= 2, "At least 2 options");

        gameCount++;
        Game storage g = games[gameCount];
        g.id = gameCount;
        g.description = _desc;
        g.condition = _condition;
        g.avatarUrl = _avatarUrl;
        g.detailedInfo = _detailedInfo;
        g.optionNames = _optionNames;
        g.optionCount = uint8(_optionNames.length);
        g.deadline = block.timestamp + _duration;

        // 初始化 AMM 虚拟流动性池 (赋予初始权重 100 虚拟 BKC，防止除以 0 导致溢出)
        for(uint8 i = 0; i < g.optionCount; i++) {
            g.virtualReserves[i] = 100 ether;
        }
    }

    // 2. 基于动态联合曲线的 AMM 份额购买逻辑
    function buyShares(uint256 _gameId, uint8 _option) public payable {
        Game storage g = games[_gameId];

        require(block.timestamp < g.deadline, "Expired");
        require(!g.isResolved && !g.isRefunded, "Closed");
        require(msg.value > 0, "Need to pay BKC");

        // 计算当前池子的总虚拟库存
        uint256 totalVirtual = 0;
        for(uint8 i = 0; i < g.optionCount; i++) {
            totalVirtual += g.virtualReserves[i];
        }

        // 动态定价公式：用户获得的份额 = 投入金额 * (总虚拟库存 / 该选项虚拟库存)
        uint256 sharesMinted = (msg.value * totalVirtual) / g.virtualReserves[_option];

        // 发放份额并记录总资金
        userShares[_gameId][msg.sender][_option] += sharesMinted;
        g.optionTotalShares[_option] += sharesMinted;
        g.totalLiquidity += msg.value;

        // 增加该选项的虚拟储备，这会自动推高它的下一次购买价格
        g.virtualReserves[_option] += msg.value;
    }

    // 3. 【全新添加】卖出份额（提前平仓锁定利润或止损）
    function sellShares(uint256 _gameId, uint8 _option, uint256 _sharesToSell) public {
        Game storage g = games[_gameId];

        // 确保比赛尚未结束，且市场未被冻结
        require(block.timestamp < g.deadline, "Game already expired");
        require(!g.isResolved && !g.isRefunded, "Game is closed");

        // 确保用户账本里有足够的份额可卖
        require(userShares[_gameId][msg.sender][_option] >= _sharesToSell, "Insufficient shares");

        // 计算当前市场总虚拟库存
        uint256 totalVirtual = 0;
        for(uint8 i = 0; i < g.optionCount; i++) {
            totalVirtual += g.virtualReserves[i];
        }

        // 动态逆向定价：理论退回金额 = 卖出份额 * (该选项虚拟库存 / 总虚拟库存)
        uint256 rawAmount = (_sharesToSell * g.virtualReserves[_option]) / totalVirtual;

        // 【防套利安全机制】扣除 2% 的手续费作为滑点保护，剩余金额退给用户
        uint256 fee = (rawAmount * 2) / 100;
        uint256 returnAmount = rawAmount - fee;

        // 确保卖出后不会将虚拟底仓彻底掏空，维持 AMM 曲线健康
        require(g.virtualReserves[_option] - returnAmount >= 10 ether, "Reserve too low after sell");

        // 状态更新（遵循 CEI 安全范式，先平账后转账）
        userShares[_gameId][msg.sender][_option] -= _sharesToSell;
        g.optionTotalShares[_option] -= _sharesToSell;

        // 从虚拟底仓和总真实资金池中扣除要退给用户的钱
        g.virtualReserves[_option] -= returnAmount;
        g.totalLiquidity -= returnAmount;

        // 物理转账：将真实的 BKC 转回给用户钱包
        payable(msg.sender).transfer(returnAmount);
    }

    // 4. 预言机清算开奖
    function resolveGame(uint256 _gameId, uint8 _winningOption, bytes32 _vdfProof) public {
        require(msg.sender == officialOracle, "Not Oracle");
        Game storage g = games[_gameId];
        g.isResolved = true;
        g.winningOption = _winningOption;
    }

    // 5. 触发流局退款
    function triggerRefund(uint256 _gameId) public {
        Game storage g = games[_gameId];
        require(block.timestamp > g.deadline && !g.isResolved, "Active");
        g.isRefunded = true;
    }

    // 6. 基于持有份额 (Shares) 的清算提现
    function claimReward(uint256 _gameId, uint8 _option) public {
        Game storage g = games[_gameId];

        uint256 sharesOwned = userShares[_gameId][msg.sender][_option];
        require(sharesOwned > 0, "No shares");

        uint256 payout = 0;
        if (g.isRefunded) {
            // 流局退款时，按照持有的份额比例退还大致本金
            payout = (sharesOwned * g.totalLiquidity) / g.optionTotalShares[_option];
        } else {
            require(g.isResolved && _option == g.winningOption, "Not winner shares");
            // 赢家拿走池子里真正的总资金：(你的份额 / 赢家选项发行的总份额) * 真实资金池总量
            payout = (sharesOwned * g.totalLiquidity) / g.optionTotalShares[g.winningOption];
        }

        userShares[_gameId][msg.sender][_option] = 0;
        payable(msg.sender).transfer(payout);
    }

    // 7. 基础信息查询接口
    function getGameInfo(uint256 _id) public view returns (
        string memory, string memory, string memory, string memory,
        string[] memory, uint8, uint256, bool, uint8, uint256, bool
    ) {
        Game storage g = games[_id];
        return (
            g.description, g.condition, g.avatarUrl, g.detailedInfo,
            g.optionNames, g.optionCount, g.totalLiquidity, g.isResolved,
            g.winningOption, g.deadline, g.isRefunded
        );
    }

    // 8. 配合 AMM 修改的扩展数据接口
    function getGameExtraData(uint256 _gameId, address _user) public view returns (uint256[] memory reserves, uint256[] memory stakesOrShares) {
        Game storage g = games[_gameId];

        uint256[] memory _reserves = new uint256[](g.optionCount);
        uint256[] memory _shares = new uint256[](g.optionCount);

        for (uint8 i = 0; i < g.optionCount; i++) {
            _reserves[i] = g.virtualReserves[i];
            _shares[i] = userShares[_gameId][_user][i];
        }
        return (_reserves, _shares);
    }

    // 9. 批量获取大盘数据
    function getBatchGames() public view returns (
        uint256[] memory ids,
        string[] memory descriptions,
        string[] memory avatarUrls,
        uint256[] memory totalPools,
        bool[] memory resolvedStates,
        uint256[] memory deadlines,
        uint256[] memory optionCounts
    ) {
        ids = new uint256[](gameCount);
        descriptions = new string[](gameCount);
        avatarUrls = new string[](gameCount);
        totalPools = new uint256[](gameCount);
        resolvedStates = new bool[](gameCount);
        deadlines = new uint256[](gameCount);
        optionCounts = new uint256[](gameCount);

        for (uint256 i = 1; i <= gameCount; i++) {
            Game storage g = games[i];
            ids[i-1] = g.id;
            descriptions[i-1] = g.description;
            avatarUrls[i-1] = g.avatarUrl;
            totalPools[i-1] = g.totalLiquidity;
            resolvedStates[i-1] = g.isResolved;
            deadlines[i-1] = g.deadline;
            optionCounts[i-1] = uint256(g.optionCount);
        }

        return (ids, descriptions, avatarUrls, totalPools, resolvedStates, deadlines, optionCounts);
    }
}