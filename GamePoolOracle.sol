// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract PredictionMarket {
    address public officialOracle;

    struct Game {
        uint256 id;
        string description;
        string condition;
        string avatarUrl;      // 🌟 新增：头像网络链接
        string detailedInfo;   // 🌟 新增：博弈池详细介绍
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

    // 🌟 修改：在创建方法中加入 _avatarUrl 和 _detailedInfo
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
        g.avatarUrl = _avatarUrl;       // 保存头像
        g.detailedInfo = _detailedInfo; // 保存详细信息
        g.optionNames = _optionNames;
        g.optionCount = uint8(_optionNames.length);
        g.deadline = block.timestamp + _duration;
    }

    function stakeTokens(uint256 _gameId, uint8 _option) public payable {
        Game storage g = games[_gameId];
        require(block.timestamp < g.deadline, "Expired");
        require(!g.isResolved && !g.isRefunded, "Closed");
        g.optionPools[_option] += msg.value;
        g.totalPool += msg.value;
        stakes[_gameId][msg.sender][_option] += msg.value;
    }

    function resolveGame(uint256 _gameId, uint8 _winningOption, bytes32 _vdfProof) public {
        require(msg.sender == officialOracle, "Not Oracle");
        Game storage g = games[_gameId];
        g.isResolved = true;
        g.winningOption = _winningOption;
    }

    function triggerRefund(uint256 _gameId) public {
        Game storage g = games[_gameId];
        require(block.timestamp > g.deadline && !g.isResolved, "Active");
        g.isRefunded = true;
    }

    function claimReward(uint256 _gameId, uint8 _option) public {
        Game storage g = games[_gameId];
        uint256 userStake = stakes[_gameId][msg.sender][_option];
        require(userStake > 0, "No stake");
        uint256 payout = 0;
        if (g.isRefunded) {
            payout = userStake;
        } else {
            require(g.isResolved && _option == g.winningOption, "Not winner");
            payout = (userStake * g.totalPool) / g.optionPools[g.winningOption];
        }
        stakes[_gameId][msg.sender][_option] = 0;
        payable(msg.sender).transfer(payout);
    }

    // 🌟 修改：基础信息查询接口，增加返回 avatarUrl 和 detailedInfo
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

    // 独立获取资金池和质押数据的接口保持不变
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
}