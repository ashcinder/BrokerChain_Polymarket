// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract PredictionMarket {
    address public officialOracle;

    struct Game {
        uint256 id;
        string description;
        string condition;
        string avatarUrl;      // 头像网络链接
        string detailedInfo;   // 博弈池详细介绍
        string[] optionNames;
        uint8 optionCount;
        uint256 totalPool;
        mapping(uint8 => uint256) optionPools;
        bool isResolved;
        uint8 winningOption;
        uint256 deadline;
        bool isRefunded;
    }

    uint256 public gameCount;
    mapping(uint256 => Game) public games;
    mapping(uint256 => mapping(address => mapping(uint8 => uint256))) public stakes;

    constructor() {
        officialOracle = msg.sender;
    }

    // 创建新的博弈池
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
    }

    // 用户质押代币（买入选项）
    function stakeTokens(uint256 _gameId, uint8 _option) public payable {
        Game storage g = games[_gameId];

        require(block.timestamp < g.deadline, "Expired");
        require(!g.isResolved && !g.isRefunded, "Closed");

        g.optionPools[_option] += msg.value;
        g.totalPool += msg.value;
        stakes[_gameId][msg.sender][_option] += msg.value;
    }

    // 预言机清算开奖
    function resolveGame(uint256 _gameId, uint8 _winningOption, bytes32 _vdfProof) public {
        require(msg.sender == officialOracle, "Not Oracle");

        Game storage g = games[_gameId];
        g.isResolved = true;
        g.winningOption = _winningOption;
    }

    // 触发流局退款
    function triggerRefund(uint256 _gameId) public {
        Game storage g = games[_gameId];

        require(block.timestamp > g.deadline && !g.isResolved, "Active");
        g.isRefunded = true;
    }

    // 用户领取奖励或退款
    function claimReward(uint256 _gameId, uint8 _option) public {
        Game storage g = games[_gameId];

        uint256 userStake = stakes[_gameId][msg.sender][_option];
        require(userStake > 0, "No stake");

        uint256 payout = 0;
        if (g.isRefunded) {
            payout = userStake; // 流局全额退款
        } else {
            require(g.isResolved && _option == g.winningOption, "Not winner");
            // 按投入比例瓜分总资金池
            payout = (userStake * g.totalPool) / g.optionPools[g.winningOption];
        }

        stakes[_gameId][msg.sender][_option] = 0;
        payable(msg.sender).transfer(payout);
    }

    // 基础信息查询接口
    function getGameInfo(uint256 _id) public view returns (
        string memory, string memory, string memory, string memory,
        string[] memory, uint8, uint256, bool, uint8, uint256, bool
    ) {
        Game storage g = games[_id];
        return (
            g.description, g.condition, g.avatarUrl, g.detailedInfo,
            g.optionNames, g.optionCount, g.totalPool, g.isResolved,
            g.winningOption, g.deadline, g.isRefunded
        );
    }

    // 获取特定资金池及某个用户的质押数据
    function getGameExtraData(uint256 _gameId, address _user) public view returns (uint256[] memory pools, uint256[] memory userStakes) {
        Game storage g = games[_gameId];

        uint256[] memory _pools = new uint256[](g.optionCount);
        uint256[] memory _userStakes = new uint256[](g.optionCount);

        for (uint8 i = 0; i < g.optionCount; i++) {
            _pools[i] = g.optionPools[i];
            _userStakes[i] = stakes[_gameId][_user][i];
        }
        return (_pools, _userStakes);
    }

    // ==========================================
    // 🌟 新增：批量获取大盘数据（性能优化核心）
    // ==========================================
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
            // 数组索引从 0 开始，而 game ID 从 1 开始
            ids[i-1] = g.id;
            descriptions[i-1] = g.description;
            avatarUrls[i-1] = g.avatarUrl;
            totalPools[i-1] = g.totalPool;
            resolvedStates[i-1] = g.isResolved;
            deadlines[i-1] = g.deadline;
            optionCounts[i-1] = uint256(g.optionCount);
        }

        return (ids, descriptions, avatarUrls, totalPools, resolvedStates, deadlines, optionCounts);
    }
}