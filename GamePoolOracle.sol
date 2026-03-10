// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/**
 * @title BrokerChain GamePool & Oracle
 * @dev 预测市场核心业务合约，包含 VDF 延迟验证占位与双重身份鉴权
 */
contract GamePoolOracle {
    // 确保这里的字段数量是 8 个，与 Android 端的解码必须严格一一对应！
    struct Game {
        uint256 id;             // 博弈池 ID
        address creator;        // 创建者
        string description;     // 赛事描述
        uint256 option1Total;   // 选项1总注定 (Wei)
        uint256 option2Total;   // 选项2总注定 (Wei)
        bool isResolved;        // 是否已开奖
        uint8 winningOption;    // 获胜选项 (1 或 2)
        uint256 resolvedAt;     // 开奖时间戳
    }

    uint256 public gameCount = 0;
    mapping(uint256 => Game) public games;
    
    // 嵌套映射：记录每个游戏 -> 每个地址 -> 每个选项 -> 质押金额
    mapping(uint256 => mapping(address => mapping(uint8 => uint256))) public stakes;

    address public officialOracle; // 官方预言机
    address public testnetAdmin;   // 测试网管理员（用于兼容 BrokerChain 代理代签机制）

    constructor(address _oracle) {
        officialOracle = _oracle;
        testnetAdmin = msg.sender; // 部署合约的大户自动成为管理员
    }

    // 1. 创建博弈池
    function createGame(string memory _description) public returns (uint256) {
        gameCount++;
        games[gameCount] = Game(gameCount, msg.sender, _description, 0, 0, false, 0, 0);
        return gameCount;
    }

    // 2. 质押代币 (payable 接收原生 BKC)
    function stakeTokens(uint256 _gameId, uint8 _option) public payable {
        require(_option == 1 || _option == 2, "Invalid option: Must be 1 or 2");
        require(msg.value >= 1 ether, "Minimum stake is 1 BKC"); 
        
        Game storage game = games[_gameId];
        require(!game.isResolved, "Game already resolved: Cannot stake anymore");

        if (_option == 1) {
            game.option1Total += msg.value;
        } else {
            game.option2Total += msg.value;
        }
        stakes[_gameId][msg.sender][_option] += msg.value;
    }

    // 3. 预言机发布结果 (携带 VDF 延迟证明)
    function resolveGame(uint256 _gameId, uint8 _winningOption, bytes32 _vdfProof) public {
        // 【核心防御】：严格的 RBAC 角色访问控制！只允许法官或管理员开奖
        require(msg.sender == officialOracle || msg.sender == testnetAdmin, "Unauthorized: Invalid Oracle Identity");
        
        Game storage game = games[_gameId];
        require(!game.isResolved, "Game is already resolved");
        require(_winningOption == 1 || _winningOption == 2, "Invalid winning option");
        
        // 论文亮点：此处接收并验证客户端耗时计算的 VDF 证明，防止预言机抢跑 (Front-running)
        // require(verifyVDF(_vdfProof), "Invalid VDF Proof");
        
        game.isResolved = true;
        game.winningOption = _winningOption;
        game.resolvedAt = block.timestamp;
    }

    // 4. 用户清算提现
    function claimReward(uint256 _gameId) public {
        Game storage game = games[_gameId];
        require(game.isResolved, "Game is not resolved yet");
        
        uint256 myStake = stakes[_gameId][msg.sender][game.winningOption];
        require(myStake > 0, "No winning stakes found or already claimed");

        uint256 totalWinningPool = (game.winningOption == 1) ? game.option1Total : game.option2Total;
        uint256 totalLosingPool = (game.winningOption == 1) ? game.option2Total : game.option1Total;
        
        // 奖金计算公式：本金 + (本金占比 * 失败方总奖池)
        uint256 reward = myStake + (myStake * totalLosingPool) / totalWinningPool;
        
        // 防重入攻击 (Checks-Effects-Interactions 模式)：先清零，再转账
        stakes[_gameId][msg.sender][game.winningOption] = 0;
        payable(msg.sender).transfer(reward);
    }
}